# GawharaStatus

إضافة تعرض حالة الخادم في قناة ديسكورد (رسالة واحدة تُعدَّل تلقائيًا):
يعمل / جارٍ إعادة التشغيل / متوقف، مع عدد اللاعبين من الحد الأقصى وعنوان الدخول.

- ضع رابط الـ Webhook في `plugins/GawharaStatus/config.yml` ثم نفّذ `/serverstatus reload`.
- الأوامر: `/serverstatus` (تحديث) | `reload` | `reset` (إرسال رسالة جديدة). الصلاحية: `gawharastatus.admin`
- الإيقاف العادي (`stop`) يُظهر «متوقف»، وعند تشغيل الخادم من جديد تصبح الحالة «يعمل». أما «جارٍ إعادة التشغيل» فلا تظهر إلا عند تنفيذ الأمر `restart`.
- البناء: JDK 25 و Maven ← `mvn clean package` ← `target/GawharaStatus.jar`
