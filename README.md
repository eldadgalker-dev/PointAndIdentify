<div dir="rtl" align="right">

# PointAndIdentify

אפליקציית Android לזיהוי היישוב שאליו מכוונת המצלמה, בדיקת נראות טופוגרפית מול מודל גבהים (DEM), ותיעוד בתמונה עם שכבת מידע ו-EXIF.

## 1. יכולות

1. מצלמה חיה עם כוונת ושכבת מידע (שם יעד, טווח, אזימוט, גלוי/מוסתר).
2. אזימוט צפון אמת מ-`TYPE_ROTATION_VECTOR`, עם remap לטלפון אנכי, החלקה ותיקון סטייה מגנטית.
3. קו ראייה מול DEM ברזולוציה של 1″ (~30 מ'), כולל עקמומיות כדור הארץ ושבירה (k=0.13).
4. משיכה מ-GitHub של אריחי גובה ברדיוס 50 ק"מ בלבד סביב המיקום (הקרובים קודם), עם מטמון מקומי ואימות SHA-256.
5. יעדים עד 50 ק"מ, כולל רצועת הגבול של המדינות השכנות: יישובים, הרים ופסגות, מגדלים ותרנים, מגדלורים, ארובות, מגדלי מים, מבצרים, חורבות ואנדרטאות.
6. שכבת נתונים במסך ובתמונה: מיקום הצופה ודיוקו, גובה עין, כיוון (אזימוט וזווית אנכית), סוג היעד, מיקומו, גובהו, טווח, אזימוט, זווית ליעד ומרווח קו ראייה. נתוני היעד נשמרים גם בתגי GPSDest ב-EXIF.
7. עדכון מאגר היעדים לפי גרסה ב-`manifest.json`.
8. כפתור **עדכון** – בדיקת ה-Release האחרון, הורדת APK, אימות SHA-256 והפעלת המתקין.

## 2. מבנה

| נתיב | תוכן |
|---|---|
| `app/` | קוד האפליקציה (Kotlin) |
| `data/manifest.json` | אינדקס האריחים והיישובים |
| `data/tiles/` | אריחי גובה `<lat>_<lon>.bin.gz` |
| `data/targets.json` | מאגר יעדים (יישובים וציוני שטח) |
| `tools/` | סקריפטי Python לבניית הנתונים |
| `.github/workflows/` | בניית Release ובדיקת נתונים |

## 3. הקמה ראשונה

1. ב-`gradle.properties` הגדר `point.githubOwner` לשם המשתמש שלך (לבנייה מקומית; ב-CI הערך נלקח אוטומטית).
2. צור Gradle wrapper: `gradle wrapper --gradle-version 8.9` (או פתיחה ב-Android Studio).
3. צור מפתח חתימה – שמור אותו מחוץ למאגר ואל תאבד אותו; עדכונים עתידיים חייבים אותו מפתח:
   `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias point`
4. צור את המאגר: `gh repo create PointAndIdentify --public --source=. --push`
5. הגדר Secrets במאגר: `POINT_KEYSTORE_B64` (פלט `base64 -w0 release.jks`), `POINT_KEYSTORE_PASSWORD`, `POINT_KEY_ALIAS`, `POINT_KEY_PASSWORD`.
6. פרסום גרסה: `git tag v1.0.0 && git push origin v1.0.0`.
7. התקנה ראשונה: הורד `PointAndIdentify.apk` מעמוד ה-Release. מכאן והלאה – כפתור **עדכון** באפליקציה.

## 4. בניית נתונים

1. `pip install -r tools/requirements.txt`
2. יעדים: `python tools/build_targets.py`. נוצרים `data/targets.json` ו-`tools/anchors.json`.
3. הורד קבצי SRTM1 (`.hgt`, 3601×3601) לתאים N29–N33 × E033–E036, לתיקייה `tools/input/` (לא נשמרת בגיט).
4. אריחים: `python tools/build_dem_tiles.py --input tools/input`. הכלי מעבד רק את רצועת ה-buffer ומדווח על תאים חסרים.
5. אם נערך קובץ נתונים ידנית: `python tools/build_manifest.py`
6. Commit ו-push של `data/` (וגם `app/src/main/assets/targets.json`).

**היקף הנתונים:** כל היעדים בעלי שם בתחום ISO3166-1=IL ב-OSM, ובנוסף יעדים במדינות השכנות שנמצאים עד 50 ק"מ מיישוב כלשהו בתחום. 50 ק"מ הוא טווח היעד של האפליקציה, ולכן יישוב רחוק יותר לא יכול להיבחר כיעד. אותו כלל קובע אילו אריחי גובה נשמרים. הפרמטר: `--buffer-km`.

**ים ונתונים חסרים:** אריח שמופיע ב-`sea.json` מטופל כגובה 0. אריח שאינו ברשימת היבשה ואינו ברשימת הים מטופל כלא ידוע. תא ללא קובץ HGT נשאר לא ידוע, אלא אם הועבר `--missing-as-sea` (ב-SRTM אין קבצים לתאי ים פתוח).

## 5. מגבלות

1. דיוק מצפן טלפון טיפוסי: ±3–5° (הערכה). ב-50 ק"מ סטייה של 4° היא כ-3.5 ק"מ הצידה.
2. עדכון עצמי – להפצה ישירה בלבד; מחייב אישור "התקנה ממקורות לא ידועים".
3. גרסה שנבנתה מקומית במפתח debug לא תתעדכן מ-Release (חתימה שונה).

## 6. מסמכים נוספים

1. [הערות מימוש, גרסה 1.0](docs/IMPLEMENTATION_NOTES.md)
2. [עמוד התקנה ועזרה](https://eldadgalker-dev.github.io/PointAndIdentify/) – מקור: `docs/index.html`. הפעלה: Settings → Pages → Deploy from a branch → `main` / `/docs`.

## 7. רישיון

1. קוד המקור מופץ תחת **BSD 3-Clause License**. הנוסח המלא בקובץ [LICENSE.txt](LICENSE.txt).
2. קובצי הנתונים תחת `data/` ו-`app/src/main/assets/` נגזרים ממקורות צד שלישי ושומרים על הרישיון המקורי שלהם (סעיף 8).

## 8. ייחוס

1. **SRTM**: NASA / USGS, נחלת הכלל.
2. **יעדים**: © OpenStreetMap contributors, רישיון ODbL.

</div>
