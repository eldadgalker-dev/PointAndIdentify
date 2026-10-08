# סביבת סוקרים חיצוניים - הנחיות ופרומפטים

גרסה 1.0. המסמך הזה נכתב כך שאפשר להעתיק אותו כמו שהוא לכל פרויקט (תיקיה מקומית או מאגר git) ולכל מחשב. הוא עצמאי: כל מה שנדרש להקמה והפעלה נמצא כאן.
עותק ראשי: `~/.claude/EXTERNAL_REVIEW.md`. בכל פרויקט נשמר עותק בשם `EXTERNAL_REVIEW.md` בתיקיית השורש, ולצידו `review-kit/` ו-`REVIEW_LOG.md` (סעיף 8). שם הפרויקט בכל הפרומפטים הוא שם הפרויקט הנוכחי.

## 1. כללי יסוד (תקפים לכל פרויקט קיים ועתידי)

1. בתחילת כל יום עבודה, לפני שכל תוכנית או קוד יוצאים לסוקר, Claude מציג רשימת סוקרים, את הבעלות על כל אחד ואת מיקום השרתים שלו, וממתין לאישור מפורש. האישור הוא ליום אחד ואינו עובר ליום הבא.
2. אסורה כל עבודה עם שרתים או שירותים שבבעלות או במיקום פיזי ברוסיה, בסין או באיראן. בעלות או מיקום שאינם ידועים נחשבים כלא מאושרים.
3. תוכניות ועיצובים נשלחים כטקסט. קוד נשלח רק אחרי אישור המשתמש בצ'אט, בשברים נפרדים, כך שאף סוקר לא מקבל יותר מ-50% מהפרויקט.
4. לעולם לא נשלחים מפתחות חתימה, סיסמאות, טוקנים, סודות של workflows או נתונים שנוצרו אוטומטית (למשל `data/` ב-PointAndIdentify).
5. סוקר מקבל גישת קריאה בלבד. אסור להפעיל סוקר במצב שמאפשר לו לכתוב קבצים או להריץ פקודות על המחשב.
6. לפני שליחת קוד מכווצים אותו (כישור `compact-for-reviewers`, אם מותקן). אין לערוך קוד מתוך טקסט מכווץ.
7. פרטים על סוקר שלא ניתן לאמת (בעלות, מיקום, גרסה) מדווחים כ"לא ידוע". אין לנחש.
8. החלטת המשתמש מ-2026-10-08: עבור Codex (OpenAI) ו-Gemini / Antigravity (Google) אין צורך להציג או לאמת מיקום שרתים, מציגים רק בעלים. האיסור על רוסיה, סין ואיראן נשאר בתוקף גם עבורם.

## 2. הסוקרים והחוזקות שלהם

סיווג זה מבוסס על ידע כללי ועל מה שאומת במחשב. בעלות ומיקום שרתים חייבים להיבדק מול תיעוד הספק בכל פעם שמבקשים אישור.

| סוקר | הפעלה | בעלים | חוזקה עיקרית | הערות |
|---|---|---|---|---|
| Antigravity IDE | מותקן ב-`D:\Antigravity IDE` (גרסה 1.107.0), לפי דיווח המשתמש עובד מצוין כסביבת עבודה. הפקודה `antigravity-ide chat` אינה מחוברת לסוכן שלו (נבדק ב-2026-10-08: אין פעילות צ'אט ביומנים ואין תשובה בחלון). לכן הוא סוקר ידני: `review.py` שומר את הפרומפט לקובץ ומעתיק אותו ללוח, המשתמש מדביק אותו בפאנל הסוכן (מצב שאלה או תכנון בלבד, בלי עריכת קבצים או הרצת פעולות) ומדביק את התשובה בחזרה |
| Codex CLI | `codex exec` | OpenAI (ארה"ב) | נכונות קוד, מקרי קצה, באגים לוגיים, בדיקות | דורש `codex login`. הפעלה תמיד עם `--sandbox read-only` |
| Gemini CLI / Antigravity | `gemini -p` (לא נתמך כעת בחשבון אישי), `antigravity-ide` (ידני) | Google (ארה"ב) | הקשר רחב, ארכיטקטורה, עקביות בין קבצים, תיעוד | הפעלה תמיד עם `--approval-mode plan` (קריאה בלבד) |
| Ollama (מקומי) | `ollama run` | קוד פתוח, רץ על המחשב | פרטיות מלאה: שום דבר לא יוצא מהמחשב | איכות תלויה במודל ובחומרה. ראו סעיף 3 |

### 2.1 סוקרי ענן נוספים שאושרו (2026-10-08)

| סוקר | הפעלה | בעלים (מדינה) | מיקום שרתים | הערות |
|---|---|---|---|---|
| Grok | `review.py ... grok`, מפתח `XAI_API_KEY` | xAI (ארה"ב) | דווח: ארה"ב, ולחלק מהמודלים גם האיחוד האירופי. מקור צד שלישי בלבד, לא אומת רשמית | לפי מקור צד שלישי: שמירה של 30 יום לניטור שימוש לרעה |
| GitHub Copilot CLI | `copilot`, התחברות GitHub | Microsoft / GitHub (ארה"ב) | לא נבדק | מותקן דרך winget. כל בקשה צורכת "premium request". הכלים shell ו-write חסומים |
| Perplexity | `review.py ... perplexity`, מפתח `PERPLEXITY_API_KEY` | Perplexity (ארה"ב) | דווח: ארה"ב (צד שלישי). לפי התיעוד הרשמי אין שמירת נתונים ב-Sonar API | שאלות ותוכניות בלבד, אסור קוד |
| Mistral / Codestral | `review.py ... mistral` או `codestral`, מפתח `MISTRAL_API_KEY` | Mistral AI (צרפת) | דווח: האיחוד האירופי כברירת מחדל (צד שלישי). לאמת ב-Trust Center של Mistral | Codestral מתאים לקוד |

1. כל אחד מהם דורש יצירת מפתח או התחברות בידי המשתמש. Claude לא מזין, לא מדפיס ולא שומר מפתחות.
2. שמות המודלים ב-`review.py` הם ניחוש שלא אומת. אפשר לדרוס אותם במשתנה `REVIEW_MODEL_<NAME>` (למשל `REVIEW_MODEL_GROK`).
3. מיקום השרתים נדרש עבורם (הוויתור חל רק על Codex ו-Gemini). הוא מוצג כ"דווח, לא אומת רשמית". אף אחד מהם אינו בבעלות חברה מרוסיה, מסין או מאיראן.
## 3. הערות על Ollama ומודלים מקומיים

1. מודל שרץ מקומית לא שולח נתונים לשרת, ולכן אין שרת בסין, ברוסיה או באיראן. למרות זאת, מודלים שמקורם בחברות סיניות (Qwen, DeepSeek, GLM, Kimi) מוחרגים כברירת מחדל. שימוש בהם דורש אישור נפרד ומפורש.
2. מודלים מומלצים לאישור: `gpt-oss` (OpenAI), `gemma` (Google), `llama` (Meta), `mistral` / `codestral` (Mistral), `phi` (Microsoft). זמינות ושמות מדויקים נבדקים ב-`ollama list` ובספריית Ollama בזמן ההקמה.
3. מודלי ענן של Ollama (שם עם הסיומת `-cloud`) אינם מקומיים. אסור להשתמש בהם בלי בדיקת בעלות ומיקום ובלי אישור.
4. הורדת מודל היא הורדה גדולה. לפני `ollama pull` מציגים שם, מקור וגודל, וממתינים לאישור.
5. מודלים שנבחרו ואושרו על ידי המשתמש (ב-2026-10-08), לשימוש בכל מחשב. הורדתם אושרה מראש, בתנאי שיש במחשב מספיק זיכרון ומקום פנוי, ושמציגים את הגודל לפני ההורדה:

| מודל | פקודת הורדה | חברה (מדינה) | תפקיד |
|---|---|---|---|
| gemma קטן | `ollama pull gemma3:1b` | Google (ארה"ב) | ביקורת כללית מהירה של תוכניות |
| phi קטן | `ollama pull phi4-mini` | Microsoft (ארה"ב) | בדיקת היגיון ועקביות |
| llama קטן | `ollama pull llama3.2:3b` | Meta (ארה"ב) | ביקורת כללית נוספת, דעה שלישית |

6. מודלים אלה קטנים. הם מתאימים לבדיקות פרטיות ולדעה נוספת, ולא כתחליף לסוקרי הענן בביקורת עומק. במחשב חזק יותר אפשר להציע גרסאות גדולות של אותן משפחות, באישור חדש.
7. שמות ותגיות עשויים להשתנות בספרייה. אם תגית לא נמצאת, מחפשים את הקרובה ביותר ומציגים אותה לאישור.

## 4. מצב ההקמה במחשב הראשי (נכון ל-2026-10-08)

| רכיב | מצב |
|---|---|
| Gemini CLI | מותקן, גרסה 0.40.0, אך **לא עובד** בחשבון אישי: שגיאת IneligibleTierError, והלקוח מפנה ל-Antigravity |
| Antigravity IDE | מותקן ב-`D:\Antigravity IDE` (גרסה 1.107.0), לפי דיווח המשתמש עובד מצוין כסביבת עבודה. הפקודה `antigravity-ide chat` אינה מחוברת לסוכן שלו (נבדק ב-2026-10-08: אין פעילות צ'אט ביומנים ואין תשובה בחלון). לכן הוא סוקר ידני: `review.py` שומר את הפרומפט לקובץ ומעתיק אותו ללוח, המשתמש מדביק אותו בפאנל הסוכן (מצב שאלה או תכנון בלבד, בלי עריכת קבצים או הרצת פעולות) ומדביק את התשובה בחזרה |
| Codex CLI | מותקן, גרסה 0.161.0, מחובר (ChatGPT). בדיקת OK עברה ב-2026-10-08 |
| Ollama | מותקן, גרסה 0.40.1, רץ. שלושה מודלים מותקנים (gemma3:1b, phi4-mini, llama3.2:3b), בדיקת OK עברה לכולם ב-2026-10-08 (6, 28 ו-40 שניות על מעבד בלבד). בטרמינל קיים צריך לפתוח חלון חדש כדי ש-PATH יתעדכן |
| סוקרי ענן נוספים | Copilot CLI 1.0.93 מותקן דרך winget (חבילת npm נכשלה בחילוץ), לא מחובר. Grok, Perplexity ו-Mistral/Codestral: ממתינים למפתחות API שהמשתמש יגדיר |

חיבור (התחברות עם חשבון, הכנסת מפתח API) מבוצע רק בידי המשתמש. Claude לא מזין סיסמאות או מפתחות.

## 5. פרומפטים

### 5.1 הפרומפט המלא והמעודכן (להדבקה ב-Claude Code בכל מחשב, גרסה 2.2)

```text
Set up and run the external review environment on this computer for the CURRENT project. Follow EXTERNAL_REVIEW.md (project root, or ~/.claude), the scripts in review-kit/, and REVIEW_LOG.md. The project name used with every review tool is the name of the current project. Reply in Hebrew, masculine, dry and professional. Start every chat line, list item and table cell with a Hebrew word; never open a line with an English term, file name, number or command, and never use an English name as a bold label at the start of an item.

A. RULES THAT ALWAYS APPLY
1. Reviewers are approved per day. At the start of every work day, before any plan or code leaves this machine, show me the list of available reviewers with their owner and wait for my explicit approval. Show the physical location of servers for every reviewer except Codex (OpenAI) and Gemini / Antigravity (Google), for which I waived the location check.
2. Never use any server, service or company owned by or physically located in Russia, China or Iran. Unknown ownership or location counts as not approved. Models from Chinese, Russian or Iranian vendors (Qwen, DeepSeek, GLM, Kimi and similar) are excluded, even when run locally, unless I approve them separately.
3. Plans and designs go as text. Code goes only after I approve it in chat, in disjoint shards (python review-kit/compact.py), no reviewer above 50% of the project. Never send keystores, passwords, tokens, workflow secrets or generated data (for example data/ folders).
4. Reviewers are read-only and run in an empty scratch directory: codex with --sandbox read-only, gemini with --approval-mode plan, Antigravity IDE (D:\Antigravity IDE) is a manual reviewer: its `chat` command is not connected to its agent (verified 2026-10-08), so review.py saves the prompt to a file and copies it to the clipboard; ask me to paste it into the IDE agent panel in ask / planning mode only (never let it edit files or run actions) and to paste its answer back to you, Ollama through the local API with no tools (python review-kit/review.py does all of this).
5. Never guess reviewer details. Anything you cannot verify is reported as unknown.
6. Do not commit or push without my approval. In a git project, write the kit, guide and log into the working tree only; the project commits them with its next normal commit.
7. Additional cloud reviewers I approved on 2026-10-08 (the per-day approval still applies): Grok (xAI, USA), GitHub Copilot CLI (Microsoft / GitHub, USA), Perplexity (USA; questions and plans only, never code) and Mistral / Codestral (Mistral AI, France). Their server locations are required and were found only in third-party sources (xAI: US and EU depending on the model; Perplexity: US; Mistral: EU by default), so show them as "reported, not officially confirmed". I create and set the keys (environment variables XAI_API_KEY, MISTRAL_API_KEY, PERPLEXITY_API_KEY) or log in to GitHub for Copilot; you never type, print or store keys. Run them only through review-kit/review.py.

B. FIRST-TIME SETUP ON A COMPUTER
1. Verify each CLI: `codex --version`, `gemini --version`, `ollama --version`. Install what is missing (npm i -g @openai/codex ; npm i -g @google/gemini-cli ; winget install --id Ollama.Ollama) and verify again. Ollama may not be on PATH in a terminal opened before the install; use the full path or open a new terminal. Copilot CLI: winget install --id GitHub.Copilot (the npm package @github/copilot failed on one computer with an EXDEV extraction error).
2. Check login (`codex login status`; gemini credentials under ~/.gemini). If a CLI is not logged in, tell me the exact command to run myself. Never type credentials or API keys for me. Known limitation: Gemini CLI fails with IneligibleTierError on a personal Google account; do not retry it, report it.
3. Install the kit: copy review-kit/review.py to ~/.claude/skills/external-reviewers/ and review-kit/compact.py to ~/.claude/skills/compact-for-reviewers/; copy the whole review-kit/ folder to ~/.claude/review-kit/ (the Drive master H:\My Drive\Transit\Dev\ModelSetup holds the same files); make sure ~/.claude/CLAUDE.md contains the "External reviewers" section from review-kit/global-rules.md and ~/.claude/EXTERNAL_REVIEW.md exists (copy from the project if missing).
4. Ollama models pre-approved by me for every computer: gemma3:1b (Google), phi4-mini (Microsoft), llama3.2:3b (Meta). Check free RAM and disk, show me the sizes, then pull them. They are small; treat them as a supplementary opinion, never as an equal vote. Any other model must be proposed (name, owner, size) and approved by me first.
5. Verify every reviewer end to end with a harmless prompt that contains no project content ("reply with the single word OK"). Report failures; do not hide them.
6. Make sure this project root contains EXTERNAL_REVIEW.md, REVIEW_LOG.md and review-kit/ (copy from ~/.claude if missing) and append a line to REVIEW_LOG.md.
7. Report in a table: reviewer, version, logged in (yes/no), read-only mode verified (yes/no), owner, server location (verified / unknown / waived), what is still blocking.

C. FIRST ACCESS TO A PROJECT EACH DAY (per computer)
1. If REVIEW_LOG.md has no SYNC-CHECK line for this computer name and today's date, run `python review-kit/sync_check.py <project dir>` before any work.
2. Git project: it fetches and reports ahead/behind, uncommitted files and commits that came from other systems. Plain folder: it compares with the master copy (drive or network folder) listed in review-kit/sync_source.txt; if the file is missing the result is UNKNOWN and you must ask me where the master copy lives.
3. Read REVIEW_LOG.md and tell me: what was already done, what is out of sync, what remains (open items). Never pull, push, overwrite or delete to fix a mismatch without my approval.
4. Append `YYYY-MM-DD HH:MM | <computer> | SYNC-CHECK | <verdict>` to REVIEW_LOG.md.
5. Then show the reviewer list for today's approval (rule A1).

D. REVIEW DEBATE (after I approve the reviewers; debate rules approved by me 2026-10-08)
1. Write a neutral brief: goal, constraints, the plan as text, open decisions with options and no stated preference, and the task "find flaws, risks, missing cases, better alternatives; be specific; do not praise; for every point give evidence, severity (high / medium / low) and a falsifiable closing condition".
2. Assign each reviewer the angle that fits its strength: Codex - correctness and edge cases; Antigravity IDE - architecture and consistency across the project (manual: I paste the prompt from the clipboard into the IDE agent panel and paste its answer back to you); local models - anything sensitive that must not leave the machine, as a supplementary opinion.
3. Round 1 is independent: run python review-kit/review.py --prompt-file BRIEF --reviewers codex antigravity-ide copilot grok mistral perplexity gemma3:1b phi4-mini llama3.2:3b --out OUTDIR (use only the reviewers I approved today) (antigravity-ide only saves the prompt to a file and the clipboard for manual pasting). Do not show reviewers each other's output in round 1.
4. Keep a point register: id, reviewer, point, evidence, severity, status (open / accepted / rejected with reason / disputed), closing condition. Merge duplicates. Never delete a dropped objection: record why it was dropped. Check every factual claim a reviewer makes before it enters the register (reviewers hallucinate; a small model invented a "round 3" in the pilot); register direct contradictions between reviewers as disputed points.
5. Answer every point yourself: accept (and change the plan), reject (with reasoning), or ask for clarification. From round 2 send each reviewer a deduplicated issue list plus your answers, keeping every original critique visible.
6. You alone never certify that a point is resolved. A point closes only when its closing condition is met or the reviewer who raised it concedes on evidence, not on persuasion alone. A point you answered inadequately stays open.
7. A real disagreement is a point still open after your answer where the objection is maintained with evidence and you reject it with reasoning, or where the facts are disputed or unknown.
8. Default maximum is 3 rounds (my decision 2026-10-08); go up to 5 only while a high-severity point is still open. Stop early when a round changes nothing material (no new points, no status changes). After the cap, report what is unresolved and why the process stopped.
9. Small local models (1B to 4B parameters) only summarize, remove duplicates and format, and check sensitive material that must stay local. They never critique as equals, never vote and never settle a dispute; verify any critique point they raise before using it.
10. Escalate to me immediately, without waiting for the end, any unresolved high-severity point or high-impact point (security, data loss, money, safety).
11. Final report to me: only the unresolved disagreements, in a table (point, each side and who holds it, evidence, your recommendation, cost of being wrong). Everything agreed in at most three lines. No praise, no filler.
E. LOG DISCIPLINE
1. Every change to these rules, tools or setup, on any computer, is appended to REVIEW_LOG.md (date, computer, what, status). Every unfinished item is listed under "Open items". Edit the master copies in ~/.claude, in H:\My Drive\Transit\Dev\ModelSetup (cross-computer master on Google Drive) and in the project's kit together and keep them identical. If that Drive path is not reachable on this computer, tell me and ask for the local path.
2. Before ending a work session, update REVIEW_LOG.md so that another computer can continue from it.
```

### 5.2 פרומפט תחילת יום עבודה

```text
Start of work day. Read EXTERNAL_REVIEW.md. Show me the table of available reviewers (installed and logged in only) with owner and the physical location of servers, mark anything unverified as "unknown", propose which reviewers to use today for this project and why, and wait for my approval. Do not send anything to any reviewer before I approve.
```

### 5.3 פרומפט דיבייט עם הסוקרים

```text
Run an external review debate on the plan below, for the current project, using only the reviewers I approved today. Follow section D of review-kit/FULL_PROMPT.md exactly (neutral brief, independent round 1, point register with evidence, severity and closing condition, no self-certified resolutions, early stop, escalation of high-impact points, local models as supplementary). Report to me only the unresolved disagreements.

PLAN:
<paste the plan as text>
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

## 8. סנכרון, יומן וערכה ניידת

1. בכל פרויקט (תיקיית git או תיקיה רגילה) נשמרים, בנוסף למסמך הזה: התיקיה `review-kit/` והקובץ `REVIEW_LOG.md`. העותק הראשי של הערכה נמצא ב-`~/.claude/review-kit/`.
2. תוכן הערכה: `review.py` (הרצת סוקרים בקריאה בלבד), `compact.py` (דחיסה ופיצול לשברים), `sync_check.py` (בדיקת סנכרון בקריאה בלבד), `global-rules.md` (נוסח הכללים הגלובליים להדבקה ב-`~/.claude/CLAUDE.md`), `FULL_PROMPT.md` (הפרומפט המלא) ו-`sync_source.txt` (נתיב עותק האב של תיקיה רגילה, אם הוגדר).
3. בפעם הראשונה בכל יום, בכל מחשב, בגישה לכל פרויקט, מריצים `python review-kit/sync_check.py <תיקיית הפרויקט>` לפני כל עבודה. הבדיקה אינה כותבת, אינה מבצעת commit או push ואינה משנה כלום.
4. בפרויקט git הבדיקה מבצעת fetch ומציגה: מצב מול origin, קבצים שלא נשמרו, וקומיטים שהגיעו ממערכות אחרות. בתיקיה רגילה היא משווה לעותק האב (דרייב או תיקיית רשת) לפי נתיב, גודל ו-SHA-256. אם עותק האב לא הוגדר, התוצאה "לא ידוע" ויש לשאול את המשתמש.
5. אחרי הבדיקה מדווחים: מה כבר בוצע (לפי היומן), מה אינו מסונכרן, ומה נותר (הסעיף "Open items"). אסור למשוך, לדחוף, לדרוס או למחוק כדי "ליישר" בלי אישור.
6. בפרויקט git ההנחיות נכתבות לעץ העבודה בלבד. הקומיט נעשה בידי הפרויקט בפעם הבאה שהוא מבצע קומיט רגיל, ולא בידי Claude.
7. כל שינוי בכללים, בכלים או בהקמה, בכל מחשב, נרשם ב-`REVIEW_LOG.md` (תאריך, שם מחשב, מה, מצב). כל פריט שלא הושלם נרשם ב-"Open items". עריכת העותקים הראשיים ב-`~/.claude` ובערכה של הפרויקט נעשית יחד, והם נשארים זהים.
8. כדי להמשיך ולערוך את ההנחיות במחשב אחר: מעתיקים את הפרויקט (או מושכים אותו מ-git), פותחים את Claude Code בתיקיה ומדביקים את הפרומפט מ-`review-kit/FULL_PROMPT.md`.
9. התיקיה הראשית היא תיקיית ה-Drive `H:\My Drive\Transit\Dev\ModelSetup` והיא היחידה הנראית מכל מחשב. `~/.claude` והעותקים בפרויקטים נגזרים ממנה. התקנה מדויקת ב-`INSTALL.md` ועדכון והפצה ב-`UPDATE.md`, שניהם בתיקיה הראשית, והכול מופעל בסקריפט `scripts\modelsetup.ps1`. עורכים תמיד קודם בתיקיה הראשית.
