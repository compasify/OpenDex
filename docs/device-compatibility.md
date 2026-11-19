# Kiểm thử tương thích thiết bị OpenDex

**Trạng thái:** Chưa thử APK trên thiết bị thật. Ma trận dưới đây là hướng dẫn kiểm thử thủ công, không phải danh sách tính năng hay thiết bị đã được xác nhận hỗ trợ.

## Phạm vi và chuẩn bị

- Dùng cùng một APK OpenDex (Kotlin/Jetpack Compose) trên cả hai máy. Cài bằng trình cài đặt Android; nếu được hỏi, chỉ cho phép cài từ nguồn APK bạn tin cậy. Không cần root, ADB cấp quyền (`adb grant`), USB debugging hay đổi cài đặt hệ thống bằng lệnh.
- **POCO Pad M1:** cấu hình đã biết là Android 16 (API 36), HyperOS 3.0; ghi lại số bản dựng thực tế. Máy không xuất hình qua USB-C, nên kiểm thử trên màn hình tablet và đánh dấu `N/A` cho bài thử màn hình ngoài có dây. OpenDex là không gian làm việc lấy cảm hứng từ Smart Dock, không phải chế độ desktop của HyperOS. Có thể thử đặt OpenDex làm ứng dụng Màn hình chính (Home) nếu ROM cho chọn; không bắt buộc.
- **Galaxy S25 Ultra:** ghi phiên bản Android, One UI và số bản dựng thực tế. Kết nối màn hình ngoài bằng bộ chuyển/hub hỗ trợ xuất hình, ưu tiên 1080p để có mốc so sánh. Bật **Samsung DeX** thủ công nếu máy chưa vào DeX, rồi mở OpenDex từ danh sách ứng dụng ngay trên màn hình DeX. Không đổi Home của điện thoại để thử DeX. Nếu chỉ thấy phản chiếu màn hình điện thoại, ghi rõ và không tính đó là phiên DeX.
- Chuẩn bị chuột và bàn phím Bluetooth hoặc USB phù hợp, cùng hai ứng dụng Android có biểu tượng mở trong danh sách ứng dụng (ví dụ Chrome và Máy tính hoặc Tệp). Trên S25, thử mở từng app trực tiếp từ danh sách ứng dụng của DeX trước để làm mốc. Trên POCO, nếu HyperOS có tùy chọn **Cửa sổ nổi**, có thể thử thủ công để đối chiếu.
- Trước khi đổi Home, ghi tên launcher hiện tại. Với mỗi lượt thử, ghi model, Android/ROM, số bản dựng firmware, phiên bản APK, ngày kiểm thử, kết nối chuột/bàn phím và (nếu có) loại hub/cáp, màn hình, độ phân giải.

**Giới hạn:** Chỉ **Notes** (Ghi chú) và **Workspace Settings** (Cài đặt không gian làm việc) là cửa sổ nội bộ của OpenDex. Dock nằm trong không gian làm việc, không phủ lên ứng dụng khác. Ứng dụng Android bên ngoài do ROM quyết định mở ở màn hình nào và ở dạng toàn màn hình, cửa sổ nổi hay cửa sổ DeX. OpenDex không ép ứng dụng ngoài vào cửa sổ nội bộ.

## Ma trận kiểm thử

Ghi **quan sát thực tế + trạng thái** vào hai cột cuối. `PASS`: đúng kỳ vọng; `FALLBACK`: đường chính bị giới hạn nhưng cách thay thế đã thử và dùng được; `FAIL`: sai kỳ vọng hoặc cách thay thế không dùng được; `CHƯA THỬ`: chưa quan sát; `N/A`: không áp dụng. Nếu chưa thử cách thay thế, không đánh dấu `FALLBACK`.

| Mã | Thao tác | Kỳ vọng | POCO Pad M1 — quan sát / trạng thái | S25 Ultra — quan sát / trạng thái |
| --- | --- | --- | --- | --- |
| DEV-01 | Đối chiếu firmware trong Cài đặt; cài APK và mở OpenDex. | Ghi đúng phiên bản; cài và mở không cần quyền đặc biệt. Trên S25, mở từ danh sách ứng dụng của DeX. | — / CHƯA THỬ | — / CHƯA THỬ |
| DEV-02 | Trên S25, kiểm tra màn hình ngoài và màn hình điện thoại riêng; xác nhận desktop/thanh tác vụ DeX gốc, rồi xác định bằng mắt OpenDex xuất hiện ở đâu. | Phân biệt DeX với phản chiếu; ghi rõ màn hình thực tế của OpenDex. Có tín hiệu hình ảnh không đồng nghĩa APK đã mở trên DeX. | Không xuất hình qua USB-C / N/A | — / CHƯA THỬ |
| IN-01 | Dùng chuột di qua dock, cuộn danh sách ứng dụng, nhấp để chọn và nhấp phải để mở menu ghim. | Phản hồi khi rê chuột rõ ràng, cuộn được, menu hiện đúng mục; có thao tác tương đương bằng cảm ứng hoặc bàn phím. | — / CHƯA THỬ | — / CHƯA THỬ |
| IN-02 | Mở danh sách ứng dụng; gõ tên, dùng Tab/Shift+Tab, phím mũi tên, Enter và Esc. | Ô tìm kiếm nhận focus, chỉ báo focus rõ; Tab không kẹt, Enter kích hoạt mục đang chọn, Esc đóng menu/danh sách. OpenDex không chiếm phím tắt hệ thống. Xác nhận kết quả mở ứng dụng ở EXT-01. | — / CHƯA THỬ | — / CHƯA THỬ |
| PIN-01 | Ghim hai app, bỏ ghim một app, đóng hẳn rồi mở lại OpenDex. | Dock giữ đúng mục còn ghim; mục đã bỏ không xuất hiện lại. Ghim không có nghĩa là app đang chạy. | — / CHƯA THỬ | — / CHƯA THỬ |
| WIN-01 | Mở đồng thời Notes và Workspace Settings; nhấp đổi cửa sổ phía trước, kéo thanh tiêu đề, đổi kích thước, thu nhỏ, khôi phục và đóng từng cửa sổ. | Hai cửa sổ nội bộ hoạt động độc lập; thao tác không tạo hay đóng cửa sổ Android bên ngoài. | — / CHƯA THỬ | — / CHƯA THỬ |
| WIN-02 | Gõ ghi chú thử rồi xoay tablet hoặc đổi kích thước cửa sổ OpenDex bằng viền gốc của DeX. | Ghi chú thử vẫn còn; cửa sổ nội bộ không nằm ngoài vùng nhìn thấy hoặc mất nút điều khiển. | — / CHƯA THỬ | — / CHƯA THỬ |
| DOCK-01 | Mở/đóng danh sách ứng dụng từ dock, chuyển sang app ngoài rồi quay lại OpenDex. | Dock và các mục ghim vẫn dùng được khi quay lại; dock không nổi lên trên ứng dụng khác. | — / CHƯA THỬ | — / CHƯA THỬ |
| EXT-01 | Từ OpenDex, mở lần lượt hai app đã chọn. Ghi mỗi app có hiện không, hiện trên màn hình nào và ở dạng gì. Nếu S25 hiện cửa sổ DeX gốc, thử kéo/đổi kích thước bằng thanh tiêu đề/viền do DeX cung cấp. | POCO: app mở theo HyperOS, chấp nhận toàn màn hình. S25: chỉ ghi thành công trên DeX khi tận mắt thấy app mục tiêu trên màn hình DeX; ghi riêng khả năng kéo/resize của cửa sổ gốc. | — / CHƯA THỬ | — / CHƯA THỬ |
| EXT-02 | Nếu mở bị từ chối, không hiện hoặc hiện sai màn hình, thử cách thay thế bên dưới. | Không crash. OpenDex không tự mở app lên điện thoại ngoài ý muốn khi người dùng đang ở DeX. Ghi lý do hiển thị (nếu có) và cách thay thế có thực sự dùng được không. | — / CHƯA THỬ | — / CHƯA THỬ |
| DISC-01 | Thực hiện **5 chu kỳ rút/cắm** theo hướng dẫn bên dưới. | Không crash, không mất mục ghim; sau khi kết nối lại, xác nhận DeX thật sự hoạt động và có thể tiếp tục dùng OpenDex. | Không có kết nối màn hình ngoài có dây / N/A | — / CHƯA THỬ |
| HOME-01 | Nếu HyperOS cho phép, chọn OpenDex làm Home, về màn hình chính rồi hoàn nguyên launcher. | Home mở đúng ứng dụng đã chọn ở từng bước; hoàn nguyên được mà không cần gỡ APK. Nếu ROM không cho chọn OpenDex, giữ nguyên launcher và thử OpenDex như app thường. | — / CHƯA THỬ | Không đổi Home khi thử DeX / N/A |

## Khi ứng dụng ngoài không mở như mong đợi

1. **POCO:** nếu OpenDex không mở được app, dùng danh sách ứng dụng của launcher hệ thống. Nếu muốn thử cửa sổ nổi, dùng tùy chọn thủ công của HyperOS nếu có. App mở toàn màn hình theo ROM không phải lỗi nếu đã mở đúng app.
2. **S25:** thử mở app từ danh sách ứng dụng ngay trên màn hình DeX. Chỉ chọn mở trên màn hình hiện tại nếu đó là màn hình bạn muốn và OpenDex có tùy chọn này. Nếu app cũng không mở được khi dùng DeX trực tiếp, ghi riêng giới hạn đó; nếu DeX mở được nhưng OpenDex không, ghi lỗi ở luồng mở từ OpenDex.
3. Ghi kết quả cho từng app, kể cả khi một app chạy còn app kia không. Đừng dùng thông báo “đã gửi yêu cầu mở” hoặc việc màn hình được nhận diện thay cho quan sát trực tiếp.

## Năm chu kỳ rút/cắm trên S25 Ultra

1. Trong DeX, mở OpenDex, ghim ít nhất một app và nhập một dòng văn bản thử trong Notes.
2. Khi OpenDex đang mở, ngắt kết nối màn hình. Quan sát trên điện thoại xem có crash hoặc app ngoài tự bật lên không. Cắm lại, vào DeX thủ công nếu cần rồi kiểm tra OpenDex trên màn hình ngoài; mở lại từ danh sách ứng dụng DeX nếu cần và ghi rõ điều đó.
3. Kiểm tra mục ghim, nội dung Notes và thao tác với dock/chuột/bàn phím. Lặp lại đến đủ 5 vòng. Điền từng dòng trước khi chấm DISC-01; không tính một lần cắm lại mà chưa xác nhận DeX.

| Chu kỳ | Sau khi rút: crash hoặc mở app ngoài ngoài ý muốn? | Sau khi cắm: DeX, vị trí OpenDex, ghim và Notes | Trạng thái |
| --- | --- | --- | --- |
| 1 | — | — | CHƯA THỬ |
| 2 | — | — | CHƯA THỬ |
| 3 | — | — | CHƯA THỬ |
| 4 | — | — | CHƯA THỬ |
| 5 | — | — | CHƯA THỬ |

## Hoàn nguyên Home trên POCO

1. Mở **Cài đặt** từ bảng cài đặt nhanh (vuốt từ mép trên, chọn biểu tượng bánh răng) nếu không vào được từ OpenDex.
2. Tìm **Ứng dụng mặc định → Ứng dụng màn hình chính (Home)** hoặc tìm từ khóa `launcher` / `màn hình chính` trong Cài đặt; đường dẫn có thể thay đổi theo firmware. Chọn lại launcher đã ghi trước lúc thử.
3. Dùng nút hoặc cử chỉ Home để xác nhận launcher cũ xuất hiện. Sau đó mở OpenDex như một ứng dụng thường nếu muốn tiếp tục kiểm thử.

## Additional display coverage (wired and wireless)

Run the existing input and window tests on the built-in display and repeat them on each external target the OS can address. Include a non-Samsung device with native Android desktop windowing or an OEM desktop where available. Record firmware, connection method, receiver, resolution, density, and the **observed** mode: extended desktop, mirroring, or media-only. A Miracast, Wireless DeX, or Google Cast receiver does not, by itself, guarantee a second app window. Google Cast screen mirroring uses the same workspace as the source device; a media receiver alone is not an Android activity target.

| ID | Test | Expected | Observed / status |
| --- | --- | --- | --- |
| DISPLAY-01 | Connect by cable or start wireless desktop. Open OpenDex from the system launcher on that display or choose it from OpenDex device diagnostics. | A separate workspace opens only when Android allows it. Confirm where it appears visually and try opening two compatible apps. No automatic main-display fallback after a rejected launch. | Untested |
| DISPLAY-02 | Resize a native OpenDex window. Where the OS permits, move that same window between displays of different densities. | System caption and controls remain usable; notes and open tool windows survive, with internal bounds fitted to the new workspace. Verify dock, hover, right-click, scroll, Tab/arrows/Enter/Esc and touch equivalents where available. | Untested |
| DISPLAY-03 | Stop wireless desktop/cast while using the workspace. Repeat five times. On reconnect, observe where Android restores or reopens the workspace. | No crash, unexpected launch, or loss of pins and notes. If Android migrates the existing window, its open tools follow it. | Untested |
| DISPLAY-04 | Try system screen mirroring (e.g. Miracast or Google Cast) and, separately, a media-only Cast session. | Mirroring shows and uses the source workspace; media-only Cast does not run an OpenDex window. Do not report either as extended desktop support. | Untested |
