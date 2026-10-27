# ADR-0007: Xác thực bằng JWT access token + refresh token lưu Redis

- **Status:** Accepted
- **Date:** 2026-10-27

## Context
App phải stateless để scale ngang (HNFR-03). Endpoint flash-sale chịu hàng nghìn req/s, nên việc xác thực không được tra DB mỗi request. Đồng thời phải **thu hồi được** phiên đăng nhập: khi logout, khi đổi mật khẩu thì thu hồi mọi phiên (SEC-02, SEC-03). Hai role là CUSTOMER và ADMIN (SEC-04). Client là SPA React chạy trên cùng domain với API sau load balancer.

## Decision
- **Access token**: JWT ký HS256, TTL **15 phút**, claims gồm `sub` (userId), `role`, `jti`, `iat`, `exp`. Gửi qua header `Authorization: Bearer`. Server chỉ kiểm tra chữ ký và hạn, không tra DB hay Redis.
- **Refresh token**: chuỗi ngẫu nhiên không đoán được (hoặc JWT có `jti`), TTL **7 ngày**, lưu trong Redis `rt:{userId}:{jti}`. Mỗi lần dùng thì **rotate** (cấp token mới, xoá token cũ). Nếu một refresh token đã dùng bị gửi lại thì thu hồi toàn bộ phiên của user đó.
- `/auth/logout` xoá refresh token. Đổi mật khẩu hoặc xoá tài khoản thì xoá `rt:{userId}:*`.
- Access token đã phát vẫn hợp lệ tối đa 15 phút. Với user bị khoá, có thể thêm denylist `jti` trong Redis nếu cần.
- Secret ký JWT lấy từ biến môi trường hoặc Secrets Manager (SEC-09). userId luôn lấy từ token, không bao giờ lấy từ body (SEC-07).
- Frontend giữ access token trong bộ nhớ. Refresh token trước mắt lưu ở storage; có thể chuyển sang cookie `HttpOnly; Secure; SameSite=Strict` mà không đổi API.

## Alternatives considered
- **Session server-side** (Spring Session + Redis): thu hồi dễ, nhưng mỗi request phải tra Redis và cần cookie + CSRF. Khó dùng cho k6 hay client khác.
- **Chỉ dùng một JWT TTL dài**: đơn giản, nhưng không thu hồi được khi logout hoặc đổi mật khẩu, vi phạm SEC-03.
- **OAuth2 / OIDC provider** (Keycloak, Cognito): chuẩn và đủ tính năng, nhưng phải vận hành thêm hệ thống, quá mức cần thiết cho một role model đơn giản.
- **RS256**: hợp khi nhiều service cần verify bằng public key. Monolith thì HS256 là đủ, có thể đổi sau.

## Consequences
- Verify access token không cần I/O, nên không gây thêm độ trễ cho luồng mua.
- Có khoảng trễ tối đa 15 phút giữa lúc thu hồi và lúc access token thật sự hết hiệu lực.
- Redis trở thành phụ thuộc của luồng refresh và logout. Redis mất dữ liệu thì user phải đăng nhập lại.
- Cần test cho các trường hợp: token hết hạn, token sai chữ ký, refresh token bị dùng lại, đổi mật khẩu thì thu hồi phiên.
