# JedaTV

JedaTV adalah aplikasi sumber terbuka untuk membantu keluarga mengunci
aplikasi Android TV dengan PIN empat digit. Aplikasi ini gratis, tanpa iklan,
akun, pelacak, atau izin internet. Semua pengaturan disimpan di TV.

**Asal proyek:** JedaTV merupakan turunan dari
[TV App Lock oleh Oleksii Klots](https://github.com/oleksii-klots/tv-app-lock).
Hak cipta Oleksii Klots, [lisensi MIT asli](LICENSE), dan riwayat commit proyek
asal dipertahankan. JedaTV dikelola secara terpisah oleh
[tammawanmufti](https://github.com/tammawanmufti).

## Fitur

- Pilih aplikasi yang meminta PIN. Aplikasi baru yang muncul di daftar peluncur
  akan dikunci secara otomatis; beberapa peluncur dan komponen TV dikecualikan.
- YouTube TV bebas PIN pada Sabtu dan Minggu pukul 06.00–21.00 WIB. Di luar
  waktu itu, YouTube meminta PIN jika dipilih sebagai aplikasi terkunci.
- Atur jeda sebelum PIN diminta lagi: 0, 5, 15, 30, 60 menit, atau sampai TV
  dinyalakan ulang.
- PIN disimpan sebagai hash PBKDF2 dengan salt acak. Setelah lima percobaan
  gagal, input ditunda 30 detik.
- Antarmuka bahasa Indonesia yang dapat digunakan dengan remote TV.

Jadwal YouTube saat ini ditetapkan di
[`LockPolicy.kt`](app/src/main/java/com/sirkala/jedatv/LockPolicy.kt), belum bisa
diubah melalui antarmuka. Zona waktunya `Asia/Jakarta`.

## Membangun dan memasang

Butuh JDK 17 dan Android SDK. Bangun APK debug dengan:

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK ada di `app/build/outputs/apk/debug/app-debug.apk`. Untuk memasang lewat
ADB, aktifkan USB debugging di TV, hubungkan komputer ke jaringan yang sama,
lalu jalankan:

```bash
adb connect <ALAMAT_IP_TV>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

Di TV, berikan izin **Akses penggunaan** dan **Tampil di atas aplikasi lain**
kepada JedaTV. Buka JedaTV, buat PIN langsung dengan remote, lalu pilih
aplikasi yang akan dikunci. Jangan bagikan PIN melalui chat atau commit Git.

ID paket JedaTV adalah `com.sirkala.jedatv`. Android menganggapnya aplikasi
berbeda dari TV App Lock (`com.alexk.tvlock`), sehingga PIN dan daftar aplikasi
terkunci **tidak berpindah otomatis**. Kedua aplikasi dapat terpasang bersamaan
selama proses pindah. Setelah JedaTV siap dan diuji, hentikan atau hapus versi
lama agar kedua layanan tidak saling mengunci. Untuk memperbarui JedaTV di
masa depan tanpa menghapus pengaturannya, gunakan ID paket dan kunci
penandatanganan APK yang sama.

## Batasan

Penguncian memantau aplikasi aktif secara berkala. Layar pertama aplikasi
terkunci mungkin terlihat sesaat sebelum layar PIN muncul. Orang yang dapat
mengubah Setelan TV, mencabut izin, atau memakai ADB dapat melewati kunci ini.
JedaTV ditujukan untuk batasan penggunaan keluarga, bukan sebagai pengaman
perangkat dari pengguna dengan akses administratif. Input HDMI berada di luar
cakupan kunci aplikasi.

## Lisensi

[MIT](LICENSE). Kode asal dibuat oleh Oleksii Klots. Perubahan JedaTV dibuat
untuk proyek turunan ini.
