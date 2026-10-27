# Jeda

Jeda adalah aplikasi sumber terbuka untuk membantu keluarga mengunci
aplikasi Android TV dengan PIN empat digit. Aplikasi ini gratis, tanpa iklan,
akun, pelacak, atau izin internet. Semua pengaturan disimpan di TV.

**Asal proyek:** Jeda merupakan turunan dari
[TV App Lock oleh Oleksii Klots](https://github.com/oleksii-klots/tv-app-lock).
Hak cipta Oleksii Klots, [lisensi MIT asli](LICENSE), dan riwayat commit proyek
asal dipertahankan. Jeda dikelola secara terpisah oleh
[tammawanmufti](https://github.com/tammawanmufti).

## Fitur

- Pilih aplikasi yang meminta PIN. Aplikasi baru yang muncul di daftar peluncur
  akan dikunci secara otomatis; beberapa peluncur dan komponen TV dikecualikan.
- Orang tua dapat membuat jadwal bebas PIN per aplikasi. Pemasangan baru tidak
  memiliki jadwal bawaan. Pembaruan mempertahankan jadwal lama yang sudah ada.
- Jeda PIN sementara untuk semua aplikasi terkunci: 30 detik, 1, 3, 5, 10,
  15, 30, atau 60 menit, atau sampai TV dinyalakan ulang.
- Saat aplikasi terkunci dibuka dengan PIN, Jeda mempertahankan aktivitasnya
  agar video dapat dilanjutkan. Berpindah sebentar ke Setelan TV dan kembali
  mempertahankan sesi hingga 30 menit; keluar ke Home atau aplikasi lain
  mengakhiri sesi tersebut.
- PIN disimpan sebagai hash PBKDF2 dengan salt acak. Setelah lima percobaan
  gagal, input ditunda 30 detik.
- Antarmuka yang dapat digunakan dengan remote TV, dengan pilihan bahasa
  Indonesia, English, atau mengikuti bahasa TV.

Jadwal dapat diatur dengan remote TV. Zona waktunya `Asia/Jakarta`.

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

Build debug memakai ID `com.sirkala.jedatv.preview` agar bisa diuji berdampingan
dengan rilis Jeda tanpa menimpa data keluarga.

Di TV, berikan izin **Akses penggunaan** dan **Tampil di atas aplikasi lain**
kepada Jeda. Buka Jeda, buat PIN langsung dengan remote, lalu pilih
aplikasi yang akan dikunci. Jangan bagikan PIN melalui chat atau commit Git.

ID paket Jeda adalah `com.sirkala.jedatv`. Android menganggapnya aplikasi
berbeda dari TV App Lock (`com.alexk.tvlock`), sehingga PIN dan daftar aplikasi
terkunci **tidak berpindah otomatis**. Kedua aplikasi dapat terpasang bersamaan
selama proses pindah. Setelah Jeda siap dan diuji, hentikan atau hapus versi
lama agar kedua layanan tidak saling mengunci. Untuk memperbarui Jeda di
masa depan tanpa menghapus pengaturannya, gunakan ID paket dan kunci
penandatanganan APK yang sama.

## Batasan

Penguncian memantau aplikasi aktif secara berkala. Layar pertama aplikasi
terkunci mungkin terlihat sesaat sebelum layar PIN muncul. Orang yang dapat
mengubah Setelan TV, mencabut izin, atau memakai ADB dapat melewati kunci ini.
Jeda ditujukan untuk batasan penggunaan keluarga, bukan sebagai pengaman
perangkat dari pengguna dengan akses administratif. Input HDMI berada di luar
cakupan kunci aplikasi.

## Lisensi

[MIT](LICENSE). Hak cipta kode asal tetap milik Oleksii Klots. Hak cipta
perubahan Jeda pada proyek turunan ini dicantumkan atas nama
[tammawanmufti](https://github.com/tammawanmufti). Kedua pemberitahuan hak
cipta dan teks lisensi MIT dipertahankan dalam berkas LICENSE.
