import os
import sys
import json
import time
import logging
import threading
import feedparser
import requests
from dotenv import load_dotenv
from flask import Flask
from bs4 import BeautifulSoup
from datetime import datetime
from pathlib import Path

load_dotenv(Path(__file__).parent / ".env")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger(__name__)

BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "")
CHECK_INTERVAL = int(os.environ.get("CHECK_INTERVAL", "300"))
DATA_FILE = Path(__file__).parent / "data" / "sent_news.json"

RSS_URL = "https://www.men.gov.ma/rss.xml"
BASE_URL = "https://www.men.gov.ma"
PAGES_TO_SCRAPE = [
    {"name": "إعلانات إضافية", "url": f"{BASE_URL}/%D8%A5%D8%B9%D9%84%D8%A7%D9%86%D8%A7%D8%AA", "emoji": "📢"},
    {"name": "مباريات", "url": f"{BASE_URL}/%D9%85%D8%A8%D8%A7%D8%B1%D9%8A%D8%A7%D8%AA", "emoji": "🏆"},
    {"name": "مذكرات", "url": f"{BASE_URL}/%D9%85%D8%B0%D9%83%D8%B1%D8%A7%D8%AA", "emoji": "📝"},
    {"name": "طلبات العروض", "url": f"{BASE_URL}/%D8%B7%D9%84%D8%A8%D8%A7%D8%AA-%D8%A7%D9%84%D8%B9%D8%B1%D9%88%D8%B6", "emoji": "💼"},
    {"name": "بلاغات", "url": f"{BASE_URL}/%D8%A8%D9%84%D8%A7%D8%BA%D8%A7%D8%AA", "emoji": "📋"},
]

app = Flask(__name__)

@app.route("/")
def home():
    return "MEN.GOV.MA Telegram Bot is running!"

@app.route("/health")
def health():
    return "ok"


def load_sent():
    if DATA_FILE.exists():
        with open(DATA_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"sent_ids": []}


def save_sent(data):
    DATA_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def send_telegram(message):
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage"
    payload = {
        "chat_id": CHAT_ID,
        "text": message,
        "parse_mode": "HTML",
        "disable_web_page_preview": True,
    }
    try:
        resp = requests.post(url, json=payload, timeout=30)
        if resp.status_code == 200:
            logger.info("Message sent successfully")
            return True
        else:
            logger.error(f"Telegram API error: {resp.status_code} - {resp.text}")
            return False
    except Exception as e:
        logger.error(f"Failed to send message: {e}")
        return False


def clean_html(html_text):
    if not html_text:
        return ""
    soup = BeautifulSoup(html_text, "html.parser")
    text = soup.get_text(separator=" ", strip=True)
    return text[:300] + "..." if len(text) > 300 else text


def check_rss(sent_ids):
    new_items = []
    try:
        feed = feedparser.parse(RSS_URL)
        for entry in feed.entries:
            item_id = entry.get("id") or entry.get("guid") or entry.get("link", "")
            if item_id in sent_ids:
                continue
            title = entry.get("title", "بدون عنوان")
            link = entry.get("link", "")
            pub_date = entry.get("published", "")
            summary = clean_html(entry.get("summary", "") or entry.get("description", ""))
            new_items.append({
                "id": item_id,
                "title": title,
                "link": link,
                "date": pub_date,
                "summary": summary,
                "source": "مستجدات",
                "emoji": "📰",
            })
            sent_ids.append(item_id)
    except Exception as e:
        logger.error(f"RSS check failed: {e}")
    return new_items


def check_scrape_page(page_info, sent_ids):
    new_items = []
    try:
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        }
        resp = requests.get(page_info["url"], headers=headers, timeout=30)
        resp.encoding = "utf-8"
        soup = BeautifulSoup(resp.text, "html.parser")

        table = soup.find("table", class_="views-table")
        if not table:
            table = soup.find("table")
        if not table:
            return new_items

        rows = table.find("tbody")
        if not rows:
            return new_items

        for tr in rows.find_all("tr")[:10]:
            cells = tr.find_all("td")
            if len(cells) < 2:
                continue

            date_cell = cells[0].get_text(strip=True) if cells else ""
            title_cell = cells[1] if len(cells) > 1 else None
            link = ""
            title = ""
            if title_cell:
                a_tag = title_cell.find("a")
                if a_tag:
                    title = a_tag.get_text(strip=True)
                    href = a_tag.get("href", "")
                    if href.startswith("/"):
                        link = BASE_URL + href
                    elif href.startswith("http"):
                        link = href
                else:
                    title = title_cell.get_text(strip=True)

            if not title:
                continue

            item_id = f"{page_info['name']}_{title}"
            if item_id in sent_ids:
                continue

            doc_link = ""
            if len(cells) > 2:
                doc_a = cells[2].find("a")
                if doc_a:
                    href = doc_a.get("href", "")
                    if href.startswith("/"):
                        doc_link = BASE_URL + href
                    elif href.startswith("http"):
                        doc_link = href

            new_items.append({
                "id": item_id,
                "title": title,
                "link": doc_link or link,
                "date": date_cell,
                "summary": "",
                "source": page_info["name"],
                "emoji": page_info["emoji"],
            })
            sent_ids.append(item_id)
    except Exception as e:
        logger.error(f"Scrape failed for {page_info['name']}: {e}")
    return new_items


def format_message(item):
    msg = f"{item['emoji']} <b>{item['source']} | وزارة التربية الوطنية</b>\n\n"
    msg += f"🔹 <b>العنوان:</b> {item['title']}\n"
    if item["date"]:
        msg += f"📅 <b>التاريخ:</b> {item['date']}\n"
    if item["summary"]:
        msg += f"\n📝 {item['summary']}\n"
    if item["link"]:
        msg += f"\n🔗 <a href=\"{item['link']}\">رابط الخبر</a>"
    return msg


def check_all():
    logger.info("Checking for new items...")
    sent_data = load_sent()
    sent_ids = sent_data.get("sent_ids", [])

    all_new = []

    all_new.extend(check_rss(sent_ids))

    for page in PAGES_TO_SCRAPE:
        all_new.extend(check_scrape_page(page, sent_ids))

    if all_new:
        logger.info(f"Found {len(all_new)} new items")
        for item in all_new:
            msg = format_message(item)
            if send_telegram(msg):
                time.sleep(1)
    else:
        logger.info("No new items found")

    sent_data["sent_ids"] = sent_ids
    sent_data["last_check"] = datetime.now().isoformat()
    save_sent(sent_data)


def main():
    if not BOT_TOKEN:
        logger.error("TELEGRAM_BOT_TOKEN not set!")
        return
    if not CHAT_ID:
        logger.error("TELEGRAM_CHAT_ID not set!")
        return

    if "--once" in sys.argv:
        logger.info("Running single check...")
        check_all()
        logger.info("Done.")
        return

    logger.info(f"Bot started. Checking every {CHECK_INTERVAL} seconds.")
    logger.info(f"Monitoring: RSS feed + {len(PAGES_TO_SCRAPE)} pages")

    def run_bot():
        check_all()
        while True:
            time.sleep(CHECK_INTERVAL)
            check_all()

    bot_thread = threading.Thread(target=run_bot, daemon=True)
    bot_thread.start()

    port = int(os.environ.get("PORT", "10000"))
    app.run(host="0.0.0.0", port=port)


if __name__ == "__main__":
    main()
