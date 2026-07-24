@echo off
set CP=lib\rome-2.1.0.jar;lib\jsoup-1.17.2.jar;lib\gson-2.10.1.jar;lib\rome-utils-2.1.0.jar;lib\jdom-1.1.3.jar;lib\jdom2-2.0.6.1.jar;lib\slf4j-api-1.7.36.jar;lib\slf4j-simple-1.7.36.jar;out
java -cp %CP% bot.Main %*
