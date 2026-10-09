# HardcoreReset 1.1.0 — triển khai ngày 30/09/2026

Đã triển khai vào `/home/ubuntu/minecraft-26-2`. Forge 65.1.3 / Minecraft 26.2 / Java 25 giữ nguyên.

## File thay đổi

- `hardcorereset-src/src/main/java/local/hardcorereset/HardcoreReset.java`: state, countdown, thông báo, Spectator, ngắt kết nối và dừng trễ.
- `hardcorereset-src/build.gradle`: version 1.1.0.
- `hardcorereset-src/local-build/build.gradle`: version 1.1.0.
- `hardcorereset-src/src/main/resources/META-INF/mods.toml`: version 1.1.0; giữ metadata không yêu cầu client cài mod.
- `hardcore-reset/tests/java/local/hardcoreresettest/ResetIntegration.java`: mở rộng test Forge, capture packet, respawn thật và crash giữa countdown.
- `hardcore-reset/tests/prepare-integration.py`: fixture dùng JAR 1.1.0.
- `HARDCORE-RESET.md`: cập nhật hành vi, vận hành và cách đổi thời gian.

File mới: `hardcore-reset/tests/run-validation.py`, báo cáo này và các log/kết quả kiểm chứng có hậu tố `1.1.0`.
Test JAR/classes được build lại bên trong `hardcore-reset/tests/`; không cài test JAR vào production.

## JAR

- Cũ đã gỡ: `mods/hardcorereset-1.0.0.jar`.
- Build mới: `hardcorereset-src/build/libs/hardcorereset-1.1.0.jar`.
- Đã cài: `mods/hardcorereset-1.1.0.jar`.
- Gradle offline build thành công; checksum JAR cài trùng bản đã kiểm thử.

## Hành vi mới

Death event chỉ xác minh người chơi, giữ UUID/tên người chết đầu tiên, ghi marker đúng định dạng cũ và chuyển trạng thái chờ. Không gọi halt hoặc kick từ death event. Vanilla tiếp tục xử lý cái chết, thông báo và màn hình chết.

Ở cuối tick, mod thông báo ai chết và bắt đầu đủ 30 giây quan sát. `System.nanoTime()` cùng `TickEvent.ServerTickEvent.Post.BUS` kiểm tra deadline; không dùng Thread.sleep, không giả định server luôn đạt 20 TPS. Có thông báo 20/10/5/4/3/2/1 giây, không spam mỗi tick. Nếu server bị đứng tick, hành động diễn ra ở tick tiếp theo, không rút ngắn thời gian.

Người chết respawn hoặc đăng nhập lại trong countdown được giữ Spectator; người sống vẫn giữ gamemode. Người chết tiếp theo không đổi danh tính người gây reset đầu tiên, không ghi lại marker và không khởi tạo countdown khác.

Đến hạn: thông báo cuối → gửi disconnect packet với lý do thất bại/tên người chết/hướng dẫn reconnect → chờ ít nhất 1 giây qua tick → `halt(false)` một lần → wrapper cũ chờ Java thoát và xóa world → khởi động world ngẫu nhiên mới. Không backup.

Marker được ghi ngay khi chết, nên crash hoặc manual stop trong countdown không hủy lần wipe đã được yêu cầu. Marker lỗi dẫn tới báo lỗi và dừng qua tick để quản trị viên xử lý.

## Các phần giữ nguyên

Checksum xác nhận `hardcore-reset/lifecycle.py`, `server-wrapper.sh`, `/etc/systemd/system/minecraft.service` và `user_jvm_args.txt` không thay đổi. Giá trị `server.properties` giữ nguyên; Minecraft tự ghi lại dòng ngày giờ khi khởi động. Cổng 25566, RAM thực tế `-Xms6G -Xmx12G`, chính sách xóa và các chốt an toàn được giữ nguyên. World production giữ nguyên định danh thư mục trong quá trình triển khai; không sao chép world để test.

## Kiểm thử và kết quả

- 13 kiểm thử an toàn đường dẫn/marker/khóa world.
- 7 tình huống vòng đời: stop, crash không marker, crash trả code 0 nhưng có report, stop khi pending, xử lý pending ở lần start tiếp theo và cấu hình nguy hiểm.
- Forge thật trong namespace cách ly: mob/cancelled/unconnected death bị bỏ qua; hai người chết gần nhau chỉ lập một countdown; vanilla death chat trước Hardcore chat; respawn thật thành Spectator; login không vượt được Spectator; người sống vẫn Survival; mọi mốc chat gửi đúng một lần.
- Đo được 30.050892946 giây từ thông báo tới ngắt kết nối, 1.048155197 giây từ ngắt kết nối tới halt; một yêu cầu shutdown.
- Cưỡng bức JVM test thoát giữa countdown: marker còn hiệu lực, wrapper xóa world thất bại và tạo world mới.
- Tổng cộng bốn world test có seed khác nhau; normal stop cuối cùng giữ world test. Fixture đã dọn sạch sau kiểm thử.
- Kết nối test là mô phỏng có capture packet gửi ra, không phải kiểm tra giao diện bằng client người thật.

Production: service active, mod 1.1.0 đã nạp, TCP 25566 đang lắng nghe, một JVM Minecraft dưới wrapper, marker không tồn tại sau triển khai.

Log production:

```text
[03:30:14] [HARDCORE] hardcorereset 1.1.0 loaded; observation countdown 30 seconds
[03:30:17] Starting Minecraft server on *:25566
[03:30:18] Done (0.328s)! For help, type "help"
```

Bằng chứng: `hardcore-reset/validation-1.1.0.json`, `hardcore-reset/verification-1.1.0.txt`, `hardcore-reset/integration-tests-1.1.0.log`, `hardcorereset-src/build-1.1.0.log`.

## Đổi thời gian sau này

Sửa `RESET_DELAY_SECONDS = 30` ở dòng 28 của `HardcoreReset.java`, build lại và cài JAR rồi restart. Hằng `DISCONNECT_FLUSH_SECONDS = 1` ở dòng 29 là khoảng chờ riêng sau ngắt kết nối. Các lệnh build/deploy cụ thể nằm trong `HARDCORE-RESET.md`.
