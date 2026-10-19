# GawharaStatus

بلجن بيعرض حالة السيرفر في روم ديسكورد (رسالة واحدة بتتعدل نفسها):
أونلاين / بيترستر / أوفلاين، وعدد اللاعبين من الحد الأقصى، والـ IP.

- حط الـ Webhook في `plugins/GawharaStatus/config.yml` وبعدها `/serverstatus reload`.
- الأوامر: `/serverstatus` (تحديث) | `reload` | `reset` (يبعت رسالة جديدة). الصلاحية: `gawharastatus.admin`
- البناء: JDK 25 + Maven → `mvn clean package` → target/GawharaStatus.jar
