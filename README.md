# TurkMS

Türk filmleri ve dizileri için **TMDB v3** destekli, çok kaynaklı bir
[CloudStream 3](https://github.com/recloudstream/cloudstream) eklentisi.

TurkMS içerik barındırmaz. Film/dizi bilgilerini TMDB'den alır, oynatma
bağlantılarını üçüncü taraf sitelerden toplar ve oynatıcıya verir.

## Özellikler

- **Türkçe arayüz**: tüm katalog, arama ve detay metinleri `tr-TR`.
- **TMDB v3**: popüler filmler, vizyondakiler, yakında vizyonda, popüler
  diziler, yayındaki diziler ve haftanın trendleri.
- **Arama**: TMDB üzerinden film/dizi arama; TMDB sonuç vermezse sitelerde
  arama devreye girer.
- **Detay sayfaları**: afiş, arka plan, özet, yıl, süre, puan, türler,
  oyuncular, fragmanlar ve öneriler.
- **Dizi bölümleri**: TMDB sezon/bölüm listesi; bölüm adı, sezon, bölüm
  numarası ve görsel bilgisi.
- **Çoklu kaynak oynatma**: aynı içerik için birden fazla siteden bağlantı
  toplanır, tekrarlar ayıklanır ve kaliteye göre sıralanır. Diziler dört
  siteden, filmler YouTube'dan gelir.
- **1080p önceliği**: ayarlardan seçilen kalite (varsayılan 1080p) listede
  ilk sıraya alınır. Etiketi olmayan "auto" akışlar sahte bir çözünürlük
  göstermez, `Unknown` olarak işaretlenir.
- **Kaynak açma/kapama**: bir site bozulursa ayarlardan kapatılabilir.

## Kaynaklar

Doğrudan kazınan siteler:

| Anahtar | Site | İçerik | Oynatıcı |
| --- | --- | --- | --- |
| `canlidizi14` | canlidizi14.net | Dizi | YouTube / hlszone / JW Player |
| `ddizi` | ddizi.tel | Dizi | JW Player (Ciner MP4/HLS) |
| `diziizlenow` | diziizle.you | Dizi | YouTube |
| `trdiziizle` | trdiziizle.tv | Dizi | JW Player / Twitter HLS |
| `youtube` | youtube.com | Film | CloudStream YouTube çıkarıcısı |

Ek olarak her bağlantı, CloudStream'in yerleşik çıkarıcı kütüphanesine
(`loadExtractor`) verilir. Bu kütüphane YouTube, Vimeo, Dailymotion, Doodstream,
Filemoon, Streamtape benzeri **onlarca video sunucusunu** tanır. Yani "50+
sağlayıcı" ifadesi, 50 ayrı site kazımak değil; 5 kaynak + CloudStream'in geniş
host desteği anlamına gelir. Sayıyı abartmamak için burada açıkça yazıyoruz.

### Filmler hakkında

Kazınan dört site **yalnızca dizi** yayınlıyor (canlidizi14'ün "film" bölümü de
aslında bölümlere ayrılmış dijital dizilerden oluşuyor). Bu yüzden film
oynatma, isteğinizde geçen **YouTube** kaynağı üzerinden yapılır:

1. TMDB'den filmin Türkçe adı alınır.
2. YouTube'da `"<ad> full film türkçe izle"` araması yapılır.
3. 20 dakikadan uzun ve başlığı eşleşen videolar seçilir.
4. `watch?v=...` adresi CloudStream'in YouTube çıkarıcısına verilir.

YouTube bazı videolarda imza/yaş kısıtı nedeniyle bağlantı vermeyebilir; bu
durumda film detay sayfası açılır ama oynatma listesi boş kalır. Bu davranış
sessizce yanlış bir kaynak göstermekten daha dürüsttür.

## Kurulum

### Depo olarak ekleme (önerilen)

1. Projeyi GitHub'a push edin (varsayılan dal: `main`).
2. Actions çalıştıktan sonra `builds` dalı otomatik oluşur ve şunları içerir:
   - `TurkMS.cs3`
   - `plugins.json`
   - `repo.json`
3. CloudStream'de **Ayarlar → Eklentiler → Depo Ekle** bölümüne şu adresi girin:

   ```
   https://raw.githubusercontent.com/<KULLANICI>/<DEPO>/builds/repo.json
   ```

4. Depoyu yenileyin, **TurkMS** eklentisini yükleyin.

### Tek tıkla kurulum

Depo hazır olduktan sonra aşağıdaki bağlantı CloudStream'i açar:

```
cloudstreamrepo://raw.githubusercontent.com/<KULLANICI>/<DEPO>/builds/repo.json
```

`<KULLANICI>` ve `<DEPO>` kısımlarını kendi GitHub bilgilerinizle değiştirin.
`repo.json` içindeki `pluginLists` adresi CI tarafından `${GITHUB_REPOSITORY}`
kullanılarak üretildiği için elle düzenleme gerekmez.

### Manuel kurulum

`builds` dalındaki `TurkMS.cs3` dosyasını indirip CloudStream'de
**Ayarlar → Eklentiler → Yerel eklenti yükle** ile açabilirsiniz.

## Ayarlar

Eklenti ayarları CloudStream içinden açılır (**Eklentiler → TurkMS → Ayarlar**):

- **TMDB API anahtarı (v3)**: boş bırakılırsa yerleşik demo anahtar kullanılır.
  Kendi anahtarınızı almak için: <https://www.themoviedb.org/settings/api>
- **Kaynaklar**: dört dizi sitesini ve YouTube film aramasını tek tek açıp kapatma.
- **Tercih edilen kalite**: 1080p (varsayılan), 720p veya 4K.
- **İstek zaman aşımı**: varsayılan 20 saniye.

Ayarlar `turkms` adlı `SharedPreferences` dosyasında saklanır.

## Derleme

Gereksinimler: JDK 17, Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew make makePluginsJson
```

Çıktılar:

- `TurkMS/build/TurkMS.cs3`
- `build/plugins.json`

Windows'ta:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat make makePluginsJson
```

> Not: `com.lagradost:cloudstream3:pre-release` artefaktı Kotlin metadata 2.4.0
> ile derlendiği için proje Kotlin **2.4.0** kullanır. Resmî şablon hâlâ 2.1.0
> kullandığından eski Kotlin sürümüyle derleme
> `binary version of its metadata is 2.4.0` hatası verir.

## Proje yapısı

```
TurkMS/
├── build.gradle.kts              # Kök Gradle yapılandırması
├── TurkMS/
│   ├── build.gradle.kts          # Eklenti meta verisi
│   └── src/main/kotlin/com/turkms/
│       ├── TurkMSPlugin.kt       # Giriş noktası, sağlayıcı kaydı
│       ├── providers/            # TurkMS + 4 site sağlayıcısı
│       ├── sources/              # Site kazıyıcıları
│       ├── Tmdb.kt               # TMDB v3 istemcisi
│       ├── Mapping.kt            # Site modelleri → CloudStream modelleri
│       ├── Site.kt               # Kaynak soyutlaması + link yardımcıları
│       ├── Http.kt               # HTTP/HTML/başlık yardımcıları
│       └── Settings*.kt          # Tercihler ve ayar arayüzü
└── .github/workflows/build.yml   # .cs3 + plugins.json + repo.json üretimi
```

## Yasal uyarı

Bu eklenti hiçbir video dosyası barındırmaz veya dağıtmaz. Tüm bağlantılar
üçüncü taraf sitelere aittir. Kullanım sorumluluğu kullanıcıya aittir; lütfen
içerik sağlayıcıların kullanım koşullarına ve bulunduğunuz ülkenin yasalarına
uyun.
