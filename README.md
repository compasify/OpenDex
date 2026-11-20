# OpenDex

Desktop workspace Android viết mới bằng Kotlin và Jetpack Compose. Các tính năng cơ bản không cần root, Shizuku hay ADB. Giao diện POCO lấy cảm hứng từ cách bố trí Smart Dock; không sử dụng mã hoặc tài nguyên từ Smart Dock.

## Build và thử

Cần Android SDK 36 và JDK 17 hoặc 21. Mở project bằng Android Studio hoặc chạy:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK ở `app/build/outputs/apk/debug/app-debug.apk`. Có thể chép APK sang máy và mở để cài, hoặc tự cài khi đã bật USB debugging: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

- **POCO Pad M1:** mở OpenDex trên tablet; dock có menu Ứng dụng, ghim app, app vừa mở từ OpenDex, đồng hồ và cài đặt. Có thể vào **Cài đặt và kiểm tra thiết bị → Màn hình chính → Đặt làm màn hình chính**, rồi xác nhận trong giao diện Android. Thao tác này không bắt buộc; có thể đổi launcher lại trong cài đặt hệ thống.
- **S25 Ultra:** bật Samsung DeX trên màn hình USB-C/HDMI, rồi mở OpenDex từ danh sách app của DeX. Đặt cửa sổ OpenDex ở kích thước mong muốn bằng điều khiển DeX. Kiểm tra vị trí và loại cửa sổ của app đã mở; Android có thể giữ app đang chạy trên màn hình cũ.
- **Điều khiển:** `Ctrl+K` khi OpenDex có focus để mở tìm kiếm; trong ô tìm kiếm, `↑`/`↓` chọn kết quả và `Enter` mở app; `Tab` di chuyển focus; `Esc` đóng menu; chuột phải, nhấn giữ hoặc `Shift+F10` trên app đang focus để ghim. App mới mở từ OpenDex xuất hiện trong dock trong phiên, không phải danh sách toàn bộ task hệ thống.
- **Cửa sổ nội bộ:** Ghi chú và Cài đặt có thanh tiêu đề kéo được; kéo góc dưới bên phải để đổi kích thước. Menu bố cục có phóng to và chia trái/phải khi workspace đủ rộng. Nút thu nhỏ đưa cửa sổ về dock. Giao diện, ghim và bản nháp lưu cục bộ.
- **Màn hình ngoài và Wi‑Fi:** kết nối bằng tính năng màn hình ngoài của hệ thống. Nếu có phiên desktop riêng, mở OpenDex từ launcher trên màn đó; nếu Android cho phép mở trực tiếp, nút thử sẽ xuất hiện ở **Cài đặt và kiểm tra thiết bị → Màn hình ngoài**. Chiếu gương chỉ hiển thị lại workspace đang dùng, còn bộ nhận Google Cast chỉ phát nội dung không mở được cửa sổ OpenDex riêng. OpenDex không tự bắt đầu phiên Cast.
- **Ngôn ngữ:** mặc định tiếng Anh, hỗ trợ tiếng Việt. Trên Android hỗ trợ cài đặt ngôn ngữ theo ứng dụng, có thể chọn Anh hoặc Việt cho OpenDex.

## Giới hạn

OpenDex chỉ vẽ dock và cửa sổ nội bộ trong workspace. Cửa sổ Android thật do HyperOS/One UI quản lý; POCO Pad M1 không xuất hình trực tiếp qua USB‑C. Không có chế độ root.

## Tùy chọn: mở ứng dụng trong cửa sổ nổi qua ADB

1. Cài **APK debug OpenDex** và [Shizuku 13 trở lên](https://github.com/RikkaApps/Shizuku/releases). Khởi động Shizuku trong **chế độ ADB**, qua Wireless debugging hoặc bằng lệnh USB mà Shizuku hiển thị. Không cần root hay `adb shell pm grant`.
2. Trong OpenDex, vào **Cài đặt và kiểm tra thiết bị → Ứng dụng cửa sổ nổi (ADB) → Cho phép qua Shizuku** rồi xác nhận. Nhấp phải hoặc nhấn giữ biểu tượng ứng dụng và chọn **Mở trong cửa sổ nổi (ADB)**.

Để yêu cầu phóng to cửa sổ mà vẫn chừa chỗ cho dock, nhấp phải hoặc nhấn giữ biểu tượng app trong dock hoặc danh sách ứng dụng, rồi chọn **Phóng to, giữ thanh ứng dụng**. Tùy chọn này cần quyền Shizuku và workspace OpenDex chiếm màn hình chính. HyperOS có thể vẫn hiển thị cửa sổ nhỏ hơn vùng yêu cầu. OpenDex không thêm thanh tiêu đề, nút thu nhỏ, phóng to hay đóng vào cửa sổ của app khác; các nút đó do chế độ desktop của hệ thống cung cấp.

**Có quyền:** OpenDex yêu cầu Android mở app tương thích dưới dạng cửa sổ nổi thật **trên màn hình chính**. ROM có thể mở toàn màn hình hoặc từ chối. Với màn hình ngoài, tiếp tục dùng chế độ cửa sổ sẵn có của hệ thống, chẳng hạn DeX. **Không có quyền, Shizuku dừng, hoặc dùng bản release:** không có mục mở nổi qua ADB; dock, tìm kiếm, ghim, mở app thông thường và cửa sổ nội bộ vẫn dùng được. Thu hồi quyền trong **Ứng dụng được cấp quyền** của Shizuku; sau khi khởi động lại máy, chạy lại Shizuku nếu cần.

Xem [checklist máy thật](docs/device-compatibility.md) và [roadmap](plans/260106-0024-opendex-mvp/plan.md). Nếu chủ động muốn chạy Compose UI tests trên thiết bị/emulator đã kết nối, dùng `./gradlew :app:connectedDebugAndroidTest`.
