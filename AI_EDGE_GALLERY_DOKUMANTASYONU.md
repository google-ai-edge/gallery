# AI Edge Gallery: Kapsamlı Mimari Haritası ve Dokümantasyon

Bu doküman, Google AI Edge Gallery uygulamasının mimari yapısını, temel özelliklerini ve teknik detaylarını haritalamak amacıyla oluşturulmuştur.

## 1. Genel Bakış (Overview)

**AI Edge Gallery**, dünyanın en güçlü açık kaynaklı Büyük Dil Modellerini (LLM) mobil cihazlarda yerel (on-device) olarak çalıştırmak için geliştirilmiş öncü bir uygulamadır. Tüm yapay zeka işlemleri doğrudan cihazın donanımında gerçekleşir; bu da tam gizlilik, çevrimdışı çalışma yeteneği ve düşük gecikme süreleri sağlar. Uygulama özellikle yeni duyurulan **Gemma 4** modeli ailesini destekleyerek cihaz içi yapay zeka deneyiminin en üst noktasını sunar.

### Temel Teknolojiler
* **Google AI Edge:** Cihaz içi makine öğrenimi için temel API'ler ve araçlar (MediaPipe LLM Inference API).
* **LiteRT:** Optimize edilmiş model yürütmesi için hafif çalışma zamanı (runtime).
* **Jetpack Compose:** Uygulamanın modern ve reaktif kullanıcı arayüzü çerçevesi.
* **Hugging Face Entegrasyonu:** Modellerin keşfedilmesi ve indirilmesi için.

---

## 2. Temel Özellikler (Core Features)

1. **Ajan Yetenekleri (Agent Skills):** LLM'i pasif bir sohbet botundan proaktif bir asistana dönüştürür. Vikipedi gibi dış veri kaynakları, haritalar ve özel işlevlerle donatılabilir (JavaScript tabanlı web yetenekleri ve Yerel "Native" intent yetenekleri).
2. **Düşünme Modlu Yapay Zeka Sohbeti (AI Chat with Thinking Mode):** Karmaşık problemleri çözerken modelin adım adım düşünme sürecini (reasoning) gösterir (Gemma 4 ailesi ile uyumludur).
3. **Resimle Sor (Ask Image):** Çoklu modalite (multimodal) yetenekleri sayesinde cihaz kamerasını veya galerisini kullanarak nesneleri tanımlar ve görsel bulmacaları çözer.
4. **Sesli Yazıcı (Audio Scribe):** Yüksek verimli cihaz içi modellerle ses kayıtlarını gerçek zamanlı olarak metne dönüştürür.
5. **İstem Laboratuvarı (Prompt Lab):** Sıcaklık (temperature) ve top-k gibi model parametreleri üzerinde ince ayar yapılarak çeşitli prompt denemelerinin yapıldığı test alanı.
6. **Model Yönetimi ve Benchmark:** Özel modellerin yüklenmesi, yönetilmesi ve cihaz donanımındaki performansının (benchmark) test edilmesi için esnek bir sandbox.
7. **%100 Cihaz İçi Gizlilik (On-Device Privacy):** Tüm işlemler cihazda gerçekleştirildiğinden internet bağlantısı gerektirmez; istemler ve hassas veriler tamamen gizli kalır.
8. **Tiny Garden & Mobile Actions:** Özel olarak eğitilmiş (finetune) FunctionGemma 270m modelleri ile oyun tabanlı görevler ve çevrimdışı cihaz kontrollerini otomatikleştirme yetenekleri.

---

## 3. Mimari Harita (Architecture Map)

Proje, ağırlıklı olarak Kotlin ile yazılmış bir Android uygulamasıdır ve belirli bileşenler etrafında organize olmuştur.

### Kök Dizin Yapısı
* `/Android/`: Ana Android uygulaması kaynak kodlarını içerir.
* `/mcp/`: Model Context Protocol (MCP) deneysel entegrasyonuna ait rehberler ve detaylar.
* `/skills/`: Uygulama içindeki ajan yeteneklerine ait (yerleşik ve öne çıkan) örnekler ve metadata (`SKILL.md`) dosyaları.
* `/model_allowlist.json`: Uygulamanın desteklediği onaylanmış Hugging Face modellerinin (Gemma, Qwen vb.) yapılandırmaları.

### Android Uygulama Mimarisi (`Android/src/app/src/main/java/com/google/ai/edge/gallery`)
Uygulama, MVVM (Model-View-ViewModel) tasarım desenine sadık kalarak yapılandırılmıştır:

1. **`ui/` (Kullanıcı Arayüzü Bileşenleri):**
   Jetpack Compose ile oluşturulan tüm ekranlar ve UI bileşenleri burada bulunur.
   * `chat/`, `modelitem/`, `textandvoiceinput/`: Sohbet, model öğeleri, sesli ve metin giriş arayüzleri.
   * `home/`: Ana ekran UI'ı.
   * `llmchat/`, `llmsingleturn/`: Sohbet ve tekil oturum (single-turn) işleyicileri.
   * `modelmanager/`, `benchmark/`: Modellerin indirilmesi ve performans metriklerinin UI bileşenleri.

2. **`data/` (Veri ve Depolama Katmanı):**
   * Veri modelleri, yapılandırmalar (`Config`, `ModelAllowlist`, `SkillAllowlist`).
   * `DataStoreRepository` aracılığıyla DataStore kullanılarak yerel veri (ayarlar, beceriler, benchmark sonuçları vb.) persistasyonu.

3. **`customtasks/` (Özel Görevler ve Yetenek İşleyiciler):**
   * `agentchat/`: Ajan sohbetinin yürütüldüğü, dış yeteneklerin (`IntentHandler`, `AgentTools`) ve MCP (Model Context Protocol) yöneticisinin yer aldığı katman.
   * `mobileactions/`: Cihaz içi native işlev çağrılarını (Function Calling) yürüten altyapı.
   * `tinygarden/`: "Tiny Garden" mini oyununun logic ve tool'ları.

4. **`runtime/` (Model Yürütme Katmanı):**
   * `LlmModelHelper`, `ModelHelperExt`: MediaPipe üzerinden AI modellerinin doğrudan çalıştırılması, token üretimi ve bağlam yönetimini üstlenen çekirdek logic.
   * `aicore/`: AI Core entegrasyonlarını içerir.

5. **`worker/`:**
   * `DownloadWorker.kt`: Modellerin arka planda güvenilir bir şekilde Hugging Face'den indirilmesini yöneten WorkManager sınıfı.

6. **`di/` (Bağımlılık Enjeksiyonu):**
   * Hilt kullanılarak bağımlılıkların yönetildiği `AppModule.kt` dosyası.

---
## 4. Genişletilebilirlik ve Entegrasyon (Extensibility & Integration)

Uygulama, farklı kaynaklardan beceriler ve araçlar eklenerek zenginleştirilebilir:

### 4.1 Beceriler (Skills)
Beceriler, modelin çeşitli eylemleri gerçekleştirmesine izin veren araçlardır. İki ana grupta toplanır:
* **JavaScript Becerileri:** Modelin verilerini işleyip web bileşenlerinde UI sunmasını (ör. harita gösterme, veri getirme) sağlar. Bu yetenekler, `SKILL.md` adlı metadata dosyaları üzerinden sisteme tanıtılır.
* **Yerel (Native) Beceriler:** Doğrudan Android cihazının yeteneklerine erişir. Modelin e-posta veya kısa mesaj göndermesi gibi eylemleri `IntentHandler.kt` üzerinden işlenir.

Uygulamaya yeni bir beceri eklemenin 3 farklı yolu vardır:
1. **Topluluk Öne Çıkanlar (Featured):** Uygulama içindeki beceri yöneticisi kullanılarak otomatik eklenebilir.
2. **URL Üzerinden Yükleme:** Beceriyi barındıran sunucunun linkini vererek (Github Pages, Cloudflare vb.) hızlıca entegre edilebilir.
3. **Yerel Dosya Aktarımı (Local Import):** Android dosya sistemi üzerinden doğrudan beceri klasörü yüklenerek (örneğin adb ile `Download/` klasörüne kopyalanan dosyalarla) test edilebilir.

### 4.2 Özel Fonksiyon Çağrıları (Function Calling)
Function Calling yapısı, `MobileActionsTools.kt` sınıfında yönetilir. Sisteme yeni yetenekler eklenebilir:
* Yeni bir `ActionType` ve araç (`Tool`) tanımlanır.
* Eklenen logic, `MobileActionsViewModel.kt`'de işlenerek özel bir Android bileşenine aktarılabilir.

### 4.3 Model Context Protocol (MCP)
Uygulama, standartlaştırılmış bir bağlam protokolü olan **MCP**'yi destekler.
* MCP sunucuları yerel cihaz üzerinde veya bir bulut servisinde çalışabilir. Uygulama, sunucunun URL'sine bağlanır.
* Güvenli sunucu erişimleri, özel başlıklar (headers) ve API anahtarları tanımlanarak (örneğin Google Cloud Maps API bağlantıları) dinamik olarak kullanılabilir. Uygulama, `Gemma-4-E4B` modeli ile bu bağlamları en stabil şekilde çözümlemektedir.

---

## 5. Geliştirme ve Derleme (Development & Build)

Projeyi yerel olarak derlemek için aşağıdaki adımlar takip edilmelidir:

1. **Hugging Face Geliştirici Uygulaması (OAuth):**
   Uygulamanın model indirebilmesi için kendi Hugging Face Geliştirici kimlik bilgilerinizle yapılandırılması zorunludur.
   * `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt` dosyasında `clientId` ve `redirectUri` değerleri kendi app'inize göre değiştirilmelidir.
   * `Android/src/app/build.gradle.kts` dosyasında `manifestPlaceholders["appAuthRedirectScheme"]` değeri aynı redirect bağlantısıyla güncellenmelidir.

2. **Gradle ile Derleme:**
   Projeyi derlemek için root Android dizini içindeki Gradle wrapper kullanılmalıdır:
   ```shell
   cd Android/src/
   ./gradlew installDebug
   ```
