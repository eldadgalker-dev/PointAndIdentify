# סביבת סוקרים חיצוניים - הנחיות ופרומפטים

גרסה 1.0. המסמך הזה נכתב כך שאפשר להעתיק אותו כמו שהוא לכל פרויקט (תיקיה מקומית או מאגר git) ולכל מחשב. הוא עצמאי: כל מה שנדרש להקמה והפעלה נמצא כאן.
עותק ראשי: `~/.claude/EXTERNAL_REVIEW.md`. בכל פרויקט נשמר עותק בשם `EXTERNAL_REVIEW.md` בתיקיית השורש. שם הפרויקט בכל הפרומפטים הוא שם הפרויקט הנוכחי.

## 1. כללי יסוד (תקפים לכל פרויקט קיים ועתידי)

1. בתחילת כל יום עבודה, לפני שכל תוכנית או קוד יוצאים לסוקר, Claude מציג רשימת סוקרים, את הבעלות על כל אחד ואת מיקום השרתים שלו, וממתין לאישור מפורש. האישור הוא ליום אחד ואינו עובר ליום הבא.
2. אסורה כל עבודה עם שרתים או שירותים שבבעלות או במיקום פיזי ברוסיה, בסין או באיראן. בעלות או מיקום שאינם ידועים נחשבים כלא מאושרים.
3. תוכניות ועיצובים נשלחים כטקסט. קוד נשלח רק אחרי אישור המשתמש בצ'אט, בשברים נפרדים, כך שאף סוקר לא מקבל יותר מ-50% מהפרויקט.
4. לעולם לא נשלחים מפתחות חתימה, סיסמאות, טוקנים, סודות של workflows או נתונים שנוצרו אוטומטית (למשל `data/` ב-PointAndIdentify).
5. סוקר מקבל גישת קריאה בלבד. אסור להפעיל סוקר במצב שמאפשר לו לכתוב קבצים או להריץ פקודות על המחשב.
6. לפני שליחת קוד מכווצים אותו (כישור `compact-for-reviewers`, אם מותקן). אין לערוך קוד מתוך טקסט מכווץ.
7. פרטים על סוקר שלא ניתן לאמת (בעלות, מיקום, גרסה) מדווחים כ"לא ידוע". אין לנחש.

## 2. הסוקרים והחוזקות שלהם

סיווג זה מבוסס על ידע כללי ועל מה שאומת במחשב. בעלות ומיקום שרתים חייבים להיבדק מול תיעוד הספק בכל פעם שמבקשים אישור.

| סוקר | הפעלה | בעלים | חוזקה עיקרית | הערות |
|---|---|---|---|---|
| Codex CLI | `codex exec` | OpenAI (ארה"ב) | נכונות קוד, מקרי קצה, באגים לוגיים, בדיקות | דורש `codex login`. הפעלה תמיד עם `--sandbox read-only` |
| Gemini CLI | `gemini -p` | Google (ארה"ב) | הקשר רחב, ארכיטקטורה, עקביות בין קבצים, תיעוד | הפעלה תמיד עם `--approval-mode plan` (קריאה בלבד) |
| Ollama (מקומי) | `ollama run` | קוד פתוח, רץ על המחשב | פרטיות מלאה: שום דבר לא יוצא מהמחשב | איכות תלויה במודל ובחומרה. ראו סעיף 3 |

### 2.1 מודלים נוספים לאישור (לא מופעלים עד אישור מפורש)

| מודל / שירות | בעלים (מדינה) | חוזקה | הערה לאישור |
|---|---|---|---|
| Mistral / Codestral | Mistral AI (צרפת) | קוד, ריבוי שפות, חלופה אירופית | מיקום שרתים לאימות מול תיעוד הספק |
| Grok | xAI (ארה"ב) | ביקורת חדה, חשיבה חופשית | קיים CLI/API לא רשמי לעיתים, לאמת מקור |
| Cohere Command | Cohere (קנדה) | ניתוח מסמכים ותוכניות | פחות מותאם לקוד |
| Perplexity | Perplexity (ארה"ב) | מחקר עם מקורות, בדיקת עובדות ותלויות | לא לשלוח קוד, רק שאלות כלליות |
| GitHub Copilot CLI | Microsoft (ארה"ב) | סקירת קוד בהקשר של מאגר GitHub | מחייב חשבון GitHub |

כל המודלים שלמעלה הם הצעות. אסור להפעיל אותם לפני אישור בצ'אט.

## 3. הערות על Ollama ומודלים מקומיים

1. מודל שרץ מקומית לא שולח נתונים לשרת, ולכן אין שרת בסין, ברוסיה או באיראן. למרות זאת, מודלים שמקורם בחברות סיניות (Qwen, DeepSeek, GLM, Kimi) מוחרגים כברירת מחדל. שימוש בהם דורש אישור נפרד ומפורש.
2. מודלים מומלצים לאישור: `gpt-oss` (OpenAI), `gemma` (Google), `llama` (Meta), `mistral` / `codestral` (Mistral), `phi` (Microsoft). זמינות ושמות מדויקים נבדקים ב-`ollama list` ובספריית Ollama בזמן ההקמה.
3. מודלי ענן של Ollama (שם עם הסיומת `-cloud`) אינם מקומיים. אסור להשתמש בהם בלי בדיקת בעלות ומיקום ובלי אישור.
4. הורדת מודל היא הורדה גדולה. לפני `ollama pull` מציגים שם, מקור וגודל, וממתינים לאישור.

## 4. מצב ההקמה במחשב הראשי (נכון ל-2026-10-08)

| רכיב | מצב |
|---|---|
| Gemini CLI | מותקן, גרסה 0.40.0, קיים קובץ התחברות |
| Codex CLI | מותקן, גרסה 0.161.0, **לא מחובר**. נדרש `codex login` בידי המשתמש |
| Ollama | התקנה דרך winget הופעלה. אימות ב-`ollama --version` |

חיבור (התחברות עם חשבון, הכנסת מפתח API) מבוצע רק בידי המשתמש. Claude לא מזין סיסמאות או מפתחות.

## 5. פרומפטים

### 5.1 פרומפט הקמה מלא (להדבקה ב-Claude Code בכל מחשב)

```text
Set up the external review environment on this computer, following EXTERNAL_REVIEW.md in this project (or ~/.claude/EXTERNAL_REVIEW.md). Work in Hebrew, masculine, dry and professional, and start every chat line with a Hebrew word.

Rules that always apply:
- Reviewers are approved per day. Before any plan or code leaves this machine, show me the list of reviewers with their owner and the physical location of their servers, and wait for my explicit approval.
- Never use any server, service or company owned by or physically located in Russia, China or Iran. Unknown ownership or location counts as not approved.
- Plans go as text. Code goes only after I approve it in chat, in disjoint shards, no reviewer above 50% of the project. Never send keystores, passwords, tokens, workflow secrets or generated data.
- Reviewers are read-only: codex with --sandbox read-only, gemini with --approval-mode plan, ollama with no tools.

Steps:
1. Verify each CLI: run `codex --version`, `gemini --version`, `ollama --version`. For any missing CLI, install it (npm i -g @openai/codex ; npm i -g @google/gemini-cli ; winget install --id Ollama.Ollama) and verify again.
2. Check login state (`codex login status`; for gemini check that ~/.gemini holds credentials). If a CLI is not logged in, tell me the exact command to run myself. Never type credentials or API keys for me.
3. For Ollama, propose models (name, origin company, size on disk) and wait for approval before every `ollama pull`. Exclude models from Chinese, Russian or Iranian companies unless I approve them separately.
4. Verify each reviewer end to end with a harmless text-only prompt ("reply with the word OK"), with no project content.
5. Make sure this project contains EXTERNAL_REVIEW.md in its root (copy it from ~/.claude if missing) and that ~/.claude/CLAUDE.md contains the "External reviewers" section. Do not commit or push without my approval.
6. Report in a table: reviewer, version, logged in (yes/no), read-only mode verified (yes/no), owner, server location (verified / unknown), what is still blocking.
```

### 5.2 פרומפט תחילת יום עבודה

```text
Start of work day. Read EXTERNAL_REVIEW.md. Show me the table of available reviewers (installed and logged in only) with owner and the physical location of servers, mark anything unverified as "unknown", propose which reviewers to use today for this project and why, and wait for my approval. Do not send anything to any reviewer before I approve.
```

### 5.3 פרומפט דיבייט עם הסוקרים

```text
Run an external review debate on the plan below, for the current project, using only the reviewers I approved today.

1. Neutral brief: write the plan as text, without revealing which option I or you prefer, without leading questions, and with the constraints, goals and open decisions listed. Show me the brief only if it contains code; code requires my approval and sharding.
2. Role assignment: give each reviewer the angle that fits its strength (Codex: correctness and edge cases; Gemini: architecture and consistency across the whole project; local Ollama model: anything sensitive that must not leave the machine). Run each reviewer in read-only mode.
3. Round 1: each reviewer critiques independently. Do not show reviewers each other's output in this round.
4. Respond to every critique point yourself: accept (and change the plan), reject (with reasoning), or ask for clarification. Send your responses plus the other reviewers' points back to the reviewers.
5. Continue round after round until only points remain where you and the reviewers truly disagree, or until 5 rounds have passed. Do not stop early just because a reviewer is silent on a point.
6. Final report to me contains only the unresolved disagreements, in a table: point, each side's position and who holds it, your recommendation, and the cost of being wrong. Everything agreed is summarized in at most three lines. No praise, no filler.
```

### 5.4 תבנית לתקציר ניטרלי

```text
Project: <project name>
Goal: <one sentence>
Constraints: <list>
Plan: <the plan, as text>
Open decisions: <list, each with the available options and no stated preference>
Task: find flaws, risks, missing cases and better alternatives. Be specific. Do not praise. If you agree with a point, say so in one line.
```

## 6. הפעלה ידנית (ללא Claude)

```text
codex exec --sandbox read-only --skip-git-repo-check -C <project dir> "<prompt>"
gemini --approval-mode plan -p "<prompt>"
ollama run <model> "<prompt>"
```

הפקודות נבדקו מול `--help` של הגרסאות המותקנות ב-2026-10-08. אחרי שדרוג גרסה יש לבדוק שוב.

## 7. פרויקט חדש

1. מעתיקים את `EXTERNAL_REVIEW.md` לשורש הפרויקט.
2. מוסיפים ל-`CLAUDE.md` של הפרויקט שורה שמפנה אליו.
3. אין צורך לשנות את הכללים הגלובליים: הם חלים אוטומטית.
