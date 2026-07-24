const RSS_URL = "https://www.men.gov.ma/rss.xml";
const BASE_URL = "https://www.men.gov.ma";

const PAGES = [
  { name: "إعلانات إضافية", url: BASE_URL + "/%D8%A5%D8%B9%D9%84%D8%A7%D9%86%D8%A7%D8%AA", emoji: "\uD83D\uDCE2" },
  { name: "مباريات", url: BASE_URL + "/%D9%85%D8%A8%D8%A7%D8%B1%D9%8A%D8%A7%D8%AA", emoji: "\uD83C\uDFC6" },
  { name: "مذكرات", url: BASE_URL + "/%D9%85%D8%B0%D9%83%D8%B1%D8%A7%D8%AA", emoji: "\uD83D\uDCDD" },
  { name: "طلبات العروض", url: BASE_URL + "/%D8%B7%D9%84%D8%A8%D8%A7%D8%AA-%D8%A7%D9%84%D8%B9%D8%B1%D9%88%D8%B6", emoji: "\uD83D\uDCBC" },
  { name: "بلاغات", url: BASE_URL + "/%D8%A8%D9%84%D8%A7%D8%BA%D8%A7%D8%AA", emoji: "\uD83D\uDCCB" }
];

function parseRssItems(xml) {
  const items = [];
  const itemRegex = /<item>([\s\S]*?)<\/item>/g;
  let match;
  while ((match = itemRegex.exec(xml)) !== null) {
    const block = match[1];
    const title = extractTag(block, "title");
    const link = extractTag(block, "link");
    const pubDate = extractTag(block, "pubDate");
    const description = extractTag(block, "description");
    const guid = extractTag(block, "guid") || link || title;
    items.push({ title, link, pubDate, description, guid });
  }
  return items;
}

function extractTag(xml, tag) {
  const cdataMatch = xml.match(new RegExp("<" + tag + "[^>]*>\\s*<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>\\s*</" + tag + ">"));
  if (cdataMatch) return cdataMatch[1].trim();
  const plainMatch = xml.match(new RegExp("<" + tag + "[^>]*>([\\s\\S]*?)</" + tag + ">"));
  if (plainMatch) return plainMatch[1].trim();
  return "";
}

function stripHtml(html) {
  return html.replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&#\d+;/g, "").trim();
}

function parseScrapePage(html, pageInfo) {
  const items = [];
  const rowRegex = /<div\s+class="table-row"[^>]*>([\s\S]*?)<\/div>\s*(?=<div\s+class="table-row"|$)/gi;
  let match;
  while ((match = rowRegex.exec(html)) !== null) {
    const rowHtml = match[1];
    const cellRegex = /<div[^>]*>([\s\S]*?)<\/div>/gi;
    const cells = [];
    let cellMatch;
    while ((cellMatch = cellRegex.exec(rowHtml)) !== null) {
      cells.push(stripHtml(cellMatch[1]));
    }
    if (cells.length < 2) continue;

    const date = cells[0] || "";
    const title = cells[1] || "";

    let link = "";
    const aMatch = rowHtml.match(/<div[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>/i);
    if (aMatch) {
      const href = aMatch[1];
      if (href.startsWith("/")) link = BASE_URL + href;
      else if (href.startsWith("http")) link = href;
    }

    if (title) items.push({ title, date, link });
  }

  if (items.length === 0) {
    const trRegex = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
    while ((match = trRegex.exec(html)) !== null) {
      const rowHtml = match[1];
      const tdRegex = /<td[^>]*>([\s\S]*?)<\/td>/gi;
      const cells = [];
      let cellMatch;
      while ((cellMatch = tdRegex.exec(rowHtml)) !== null) {
        cells.push(stripHtml(cellMatch[1]));
      }
      if (cells.length < 2) continue;
      const date = cells[0] || "";
      const title = cells[1] || "";
      let link = "";
      const aMatch = rowHtml.match(/<a[^>]+href="([^"]+)"[^>]*>/i);
      if (aMatch) {
        const href = aMatch[1];
        if (href.startsWith("/")) link = BASE_URL + href;
        else if (href.startsWith("http")) link = href;
      }
      if (title) items.push({ title, date, link });
    }
  }

  return items.slice(0, 10);
}

async function sendTelegram(env, text, targetChatId) {
  const chatId = targetChatId || env.CHAT_ID;
  const resp = await fetch("https://api.telegram.org/bot" + env.BOT_TOKEN + "/sendMessage", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      chat_id: chatId,
      text: text,
      parse_mode: "HTML",
      disable_web_page_preview: true
    })
  });
  return resp.json();
}

async function checkAll(env) {
  let count = 0;
  const sentIds = new Set();
  const keys = await env.SENT_IDS.list();
  for (const key of keys.keys) sentIds.add(key.name);

  try {
    const rssResp = await fetch(RSS_URL);
    const rssXml = await rssResp.text();
    const items = parseRssItems(rssXml);

    for (const item of items) {
      const id = item.guid || item.link || item.title;
      if (sentIds.has(id)) continue;

      const summary = item.description ? stripHtml(item.description).substring(0, 300) : "";
      const msg = "\uD83D\uDCF0 <b>مستجدات | وزارة التربية الوطنية</b>\n\n"
        + "\uD83D\uDD39 <b>العنوان:</b> " + item.title + "\n"
        + (item.pubDate ? "\uD83D\uDCC5 <b>التاريخ:</b> " + item.pubDate + "\n" : "")
        + (summary ? "\n\uD83D\uDCDD " + summary + "\n" : "")
        + (item.link ? "\n\uD83D\uDD17 <a href=\"" + item.link + "\">رابط الخبر</a>" : "");

      await sendTelegram(env, msg);
      await env.SENT_IDS.put(id);
      count++;
      await new Promise(r => setTimeout(r, 1000));
    }
  } catch (e) {
    console.error("RSS failed:", e.message);
  }

  for (const page of PAGES) {
    try {
      const resp = await fetch(page.url, {
        headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" }
      });
      const html = await resp.text();
      const items = parseScrapePage(html, page);

      for (const item of items) {
        const id = page.name + "_" + item.title;
        if (sentIds.has(id)) continue;

        const msg = page.emoji + " <b>" + page.name + " | وزارة التربية الوطنية</b>\n\n"
          + "\uD83D\uDD39 <b>العنوان:</b> " + item.title + "\n"
          + (item.date ? "\uD83D\uDCC5 <b>التاريخ:</b> " + item.date + "\n" : "")
          + (item.link ? "\n\uD83D\uDD17 <a href=\"" + item.link + "\">رابط الخبر</a>" : "");

        await sendTelegram(env, msg);
        await env.SENT_IDS.put(id);
        count++;
        await new Promise(r => setTimeout(r, 1000));
      }
    } catch (e) {
      console.error("Scrape failed for " + page.name + ":", e.message);
    }
  }

  return count;
}

async function handleMessage(message, env) {
  const chatId = String(message.chat.id);
  const text = (message.text || "").trim();
  const firstName = message.from ? (message.from.first_name || "") : "";

  console.log("Received from " + firstName + " (chat " + chatId + "): " + text);

  if (text === "/start") {
    await sendTelegram(env,
      "\uD83D\uDCE3 <b>مرحباً " + firstName + "!</b>\n\n"
      + "بوت متابعة أخبار وزارة التربية الوطنية.\n\n"
      + "<b>الأوامر المتاحة:</b>\n"
      + "/check - فحص فوري للأخبار\n"
      + "/status - حالة البوت\n"
      + "/reset - مسح السجل وإعادة الفحص\n"
      + "/help - هذه الرسالة\n\n"
      + "\uD83D\uDD14 يفحص تلقائياً كل 5 دقائق.",
      chatId
    );
  } else if (text === "/help") {
    await sendTelegram(env,
      "<b>الأوامر:</b>\n"
      + "/start - بدء البوت\n"
      + "/check - فحص فوري\n"
      + "/status - حالة البوت\n"
      + "/reset - مسح السجل وفحص من جديد",
      chatId
    );
  } else if (text === "/check") {
    await sendTelegram(env, "\u23F3 جاري الفحص...", chatId);
    const count = await checkAll(env);
    if (count === 0) {
      await sendTelegram(env, "\u2705 لا أخبار جديدة. كل شيء محدث.", chatId);
    } else {
      await sendTelegram(env, "\uD83D\uDD14 تم إرسال " + count + " خبر/أخبار جديدة.", chatId);
    }
  } else if (text === "/status") {
    const keys = await env.SENT_IDS.list();
    const total = keys.keys.length;
    await sendTelegram(env,
      "\uD83D\uDCCA <b>حالة البوت</b>\n\n"
      + "\uD83C\uDF10 مصادر: RSS + " + PAGES.length + " صفحات\n"
      + "\uD83D\uDCCB أخبار مسجلة: " + total + "\n"
      + "\u23F0 التوقيت: كل 5 دقائق",
      chatId
    );
  } else if (text === "/reset") {
    const keys = await env.SENT_IDS.list();
    for (const key of keys.keys) {
      await env.SENT_IDS.delete(key.name);
    }
    await sendTelegram(env, "\uD83D\uDD04 تم مسح السجل. جاري فحص جديد...", chatId);
    const count = await checkAll(env);
    await sendTelegram(env, "\u2705 تم. وُجد " + count + " خبر.", chatId);
  } else if (text.startsWith("/")) {
    await sendTelegram(env, "\u2753 أمر غير معروف. أرسل /help للقائمة.", chatId);
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "POST") {
      try {
        const update = await request.json();
        if (update.message) {
          await handleMessage(update.message, env);
        }
        return new Response("ok");
      } catch (e) {
        return new Response("error: " + e.message, { status: 500 });
      }
    }
    return new Response("MEN.GOV.MA Bot is running");
  },

  async scheduled(event, env) {
    console.log("Cron trigger fired at " + new Date().toISOString());
    const count = await checkAll(env);
    console.log("Sent " + count + " new items");
  }
};
