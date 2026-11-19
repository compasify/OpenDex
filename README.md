# OpenDex

Desktop workspace Android viết mới bằng Kotlin và Jetpack Compose. APK thường, không root, không Shizuku và không cần cấp quyền qua ADB. Giao diện POCO lấy cảm hứng từ cách bố trí Smart Dock; không sử dụng mã hoặc tài nguyên từ Smart Dock.

## Build và thử

Cần Android SDK 36 và JDK 17 hoặc 21. Mở project bằng Android Studio hoặc chạy:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK ở `app/build/outputs/apk/debug/app-debug.apk`. Có thể chép APK sang máy và mở để cài, hoặc tự cài khi đã bật USB debugging: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Không có bước cấp quyền đặc biệt.

- **POCO Pad M1:** mở OpenDex trên tablet; dock có menu Ứng dụng, ghim app, app vừa mở từ OpenDex, đồng hồ và cài đặt. Có thể vào **Cài đặt và kiểm tra thiết bị → Màn hình chính → Chọn làm màn hình chính**, rồi xác nhận trong giao diện Android. Thao tác này không bắt buộc; có thể đổi launcher lại trong cài đặt hệ thống.
- **S25 Ultra:** bật Samsung DeX trên màn hình USB-C/HDMI, rồi mở OpenDex từ danh sách app của DeX. Đặt cửa sổ OpenDex ở kích thước mong muốn bằng điều khiển DeX. Kiểm tra vị trí và loại cửa sổ của app đã mở; Android có thể giữ app đang chạy trên màn hình cũ.
- **Điều khiển:** `Ctrl+K` khi OpenDex có focus để mở tìm kiếm; trong ô tìm kiếm, `↑`/`↓` chọn kết quả và `Enter` mở app; `Tab` di chuyển focus; `Esc` đóng menu; chuột phải, nhấn giữ hoặc `Shift+F10` trên app đang focus để ghim. App mới mở từ OpenDex xuất hiện trong dock trong phiên, không phải danh sách toàn bộ task hệ thống.
- **Cửa sổ nội bộ:** Ghi chú và Cài đặt có thanh tiêu đề kéo được; kéo góc dưới bên phải để đổi kích thước. Menu bố cục có phóng to và chia trái/phải khi workspace đủ rộng. Nút thu nhỏ đưa cửa sổ về dock. Giao diện, ghim và bản nháp lưu cục bộ.

## Giới hạn

OpenDex chỉ vẽ dock và cửa sổ nội bộ trong workspace của nó; không hiện chồng lên app khác. Việc chọn OpenDex làm màn hình chính không biến nó thành trình quản lý cửa sổ hệ thống. Cửa sổ Android thật do HyperOS/One UI kiểm soát. POCO Pad M1 không hỗ trợ xuất hình trực tiếp qua USB‑C; nhánh đặc quyền/root chưa được bổ sung.

Xem [checklist máy thật](docs/device-compatibility.md) và [roadmap](plans/260106-0024-opendex-mvp/plan.md). Nếu chủ động muốn chạy Compose UI tests trên thiết bị/emulator đã kết nối, dùng `./gradlew :app:connectedDebugAndroidTest`.
