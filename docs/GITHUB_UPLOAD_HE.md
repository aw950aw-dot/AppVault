# העלאה ל-GitHub מהטלפון

הפרויקט כולל כבר את קובץ GitHub Actions בשם `build release APK`.

1. פתח בדפדפן הטלפון את https://github.com/new.
2. צור Repository חדש בשם `AppVault` תחת החשבון שלך.
3. מומלץ לבחור Private או Public לפי רצונך. אל תוסיף README, .gitignore או License אוטומטיים — קבצים אלה כבר כלולים בפרויקט.
4. אחרי יצירת המאגר, העתק את שם המאגר המלא בפורמט `username/AppVault`.
5. מסור את שם המאגר המלא כדי שאפשר יהיה להעלות אליו את קובצי הפרויקט דרך חיבור GitHub.
6. לאחר העלאת הקבצים, פתח Actions → `build release APK` → Run workflow.
7. כשהריצה מסתיימת בהצלחה, פתח את הריצה והורד את `AppVault-APKs` מתוך Artifacts.

`app-debug.apk` הוא קובץ הבדיקה החתום במפתח debug של GitHub Actions. `app-release-unsigned.apk` אינו חתום ולכן אינו מיועד להתקנה עד שמגדירים מפתח חתימה פרטי.
