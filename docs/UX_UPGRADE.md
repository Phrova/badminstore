# Mẫu nâng cấp UX (AI dùng đúng các mẫu này, không tự chế)

Xác nhận ghi CSDL, toast, Swiper, AOS, bộ chọn số lượng… nằm ở `static/js/main.js`.
`static/js/app.js` giữ: khoá nút khi submit (ưu tiên đúng `event.submitter`), `window.toast()`,
focus ô sai đầu tiên, nút lên đầu trang, phím tắt `/` focus ô tìm kiếm, và gắn `html.no-aos`
khi CDN AOS lỗi. AI chỉ cần gắn thuộc tính HTML, không viết lại JS.

## 1. Xác nhận khi ghi DB
```html
<form th:action="@{/admin/products/{id}/delete(id=${p.id})}" method="post"
      data-confirm="Xoá sản phẩm này? Hành động không thể hoàn tác.">
  <button class="btn btn-sm btn-outline-danger" aria-label="Xoá sản phẩm">
    <i class="bi bi-trash" aria-hidden="true"></i>
  </button>
</form>
```
Áp dụng cho cả `<a data-confirm="...">`. Sau redirect, thông báo hiện qua
`fragments/flash.html` (`success` / `error` / `info`).

## 2. Bộ chọn số lượng (giỏ hàng, chi tiết sản phẩm)
```html
<div class="input-group js-quantity quantity-group">
  <button type="button" class="btn btn-outline-secondary js-quantity-down" aria-label="Giảm số lượng">
    <i class="bi bi-dash-lg" aria-hidden="true"></i>
  </button>
  <input type="number" name="quantity" class="form-control text-center" min="1" th:max="${variant.stockQuantity}" value="1">
  <button type="button" class="btn btn-outline-secondary js-quantity-up" aria-label="Tăng số lượng">
    <i class="bi bi-plus-lg" aria-hidden="true"></i>
  </button>
</div>
```

## 3. Chọn biến thể bằng chip
```html
<input type="radio" class="btn-check" name="variantId" id="v1" th:value="${v.id}" th:disabled="${v.stockQuantity == 0}">
<label class="btn variant-chip" for="v1" th:text="${v.label}">4U – G5</label>
```
Trang chi tiết sản phẩm cần chip 2 dòng nên thêm `variant-option` (CSS trong `product-detail.css`):
`class="btn variant-chip variant-option"` – giữ nguyên màu/viền/trạng thái hết hàng của `.variant-chip`.

## 4. Thanh mua cố định trên mobile (trang chi tiết)
Bọc nội dung trang bằng `class="has-sticky-buy"`, thêm cuối trang:
```html
<div class="sticky-buy d-lg-none d-flex align-items-center justify-content-between gap-2">
  <span class="price" th:text="${#numbers.formatDecimal(price,0,'POINT',0,'COMMA')} + ' ₫'"></span>
  <button class="btn btn-primary flex-grow-1" form="addToCartForm">Thêm vào giỏ</button>
</div>
```
`theme.css` tự chừa `padding-bottom` cho `.has-sticky-buy`, đẩy `#backToTop` lên trên thanh mua và thêm
padding cho footer để dòng bản quyền không bị thanh này che ở cuối trang.

## 5. Mua ngay và so sánh nhanh
Cùng form thêm vào giỏ, thêm nút thứ hai có `name` để server biết ý định:
```html
<div class="pdp-buy-actions">
  <button type="submit" class="btn btn-primary btn-lg" data-pdp-add-to-cart>
    <i class="bi bi-cart-plus" aria-hidden="true"></i> Thêm vào giỏ
  </button>
  <button type="submit" class="btn btn-accent btn-lg" name="buyNow" value="true" data-pdp-add-to-cart>
    <i class="bi bi-lightning-charge-fill" aria-hidden="true"></i> Mua ngay
  </button>
</div>
```
`CartController.add` thêm hàng vào giỏ rồi `redirect:/thanh-toan` khi `buyNow=true` (không đẩy toast
"Đã thêm vào giỏ" để trang thanh toán không bị che). Cả hai nút đều có `data-pdp-add-to-cart`
để `product-detail.js` vô hiệu hoá khi tổ hợp đang chọn hết hàng.

Thẻ sản phẩm dùng nút so sánh tròn nổi trên ảnh, không thêm hàng nút thứ hai:
```html
<form class="m-0" th:action="@{/so-sanh/them}" method="post">
  <input type="hidden" name="productId" th:value="${card.id}">
  <button class="card-compare" type="submit" title="Thêm vào so sánh"
          th:aria-label="|Thêm ${card.name} vào danh sách so sánh|">
    <i class="bi bi-bar-chart-steps" aria-hidden="true"></i>
  </button>
</form>
```
`.card-compare` đặt `z-index` trên vùng bấm của `stretched-link`; chỉ dùng icon nên bắt buộc có `aria-label`.

## 6. Thanh Lưu cố định trong form quản trị
Thay `card-footer` của form bằng `.form-actions` đặt ở cuối `<form>`:
```html
<div class="form-actions">
  <button class="btn btn-primary flex-grow-1" type="submit">
    <i class="bi bi-save" aria-hidden="true"></i> Lưu sản phẩm
  </button>
  <a class="btn btn-outline-secondary" th:href="@{/admin/san-pham}">Huỷ</a>
</div>
```
`theme.css` đặt thanh này `position: fixed` ở đáy vùng nội dung, tự chừa `padding-bottom` cho
`.admin-content:has(.form-actions)` và ẩn `#backToTop` để không đè nút Huỷ. Không dùng
`card-footer` cho nút Lưu của form nữa (chỉ dùng cho chân thẻ không phải form).

## 7. Mẫu theo trang
- **Fragment dùng lại:** `fragments/common.html` – `money`, `moneyOr(value, fallback)`, `dateTime` (null → `—`),
  `emptyState(icon, message)`; `fragments/pagination.html` – `pager(page, pageNumbers, url, query, ariaLabel, itemLabel)`
  (caller tự ghép `query` bằng `#uris.escapeQueryParam`). Đừng viết lại các đoạn này trong từng trang.
- **Danh sách:** số kết quả; chip bộ lọc đang chọn + nút "Xoá lọc"; sắp xếp; ảnh `loading="lazy"`; sản phẩm hết hàng làm mờ + nhãn; trạng thái rỗng dùng `emptyState`.
- **Chi tiết:** breadcrumb; gallery + thumbnail (không tự chạy); chip biến thể (hết hàng thì disabled); stepper `max = tồn kho`; thanh mua dính mobile; dải tin cậy (chính hãng / đổi trả); sản phẩm liên quan; tóm tắt sao đánh giá.
- **Giỏ hàng:** stepper, thành tiền từng dòng, xoá có confirm, tóm tắt `sticky-summary`, giỏ rỗng có nút "Tiếp tục mua sắm".
- **Thanh toán:** thanh bước; `type="tel" inputmode="numeric" autocomplete="tel"`, `autocomplete` cho tên/địa chỉ; lỗi dưới từng ô; nút submit loading; tóm tắt đơn thu gọn trên mobile.
- **Đơn của tôi:** tab lọc theo trạng thái, `status-pill`, timeline, huỷ đơn có confirm.
- **Admin:** sidebar thu gọn (offcanvas) trên mobile; bảng dài bọc `<div class="table-responsive table-scroll">` + `<table class="... table-sticky">`; ô tìm kiếm + lọc trên bảng; mọi thao tác ghi có confirm + toast; stat-card + Chart.js ở dashboard (canvas có `role="img"` + bảng dữ liệu ẩn).
- **Toàn site:** skip-link, breadcrumb, `aria-label` cho nút icon, `aria-hidden="true"` cho icon trang trí, ảnh có `alt`, nút lên đầu trang, favicon, trang lỗi 403/404/500 có nút về trang chủ.
- **Ảnh có thể hỏng:** thêm `th:attr="data-fallback=@{/images/no-image.svg}"` cho ảnh sản phẩm
  (thẻ sản phẩm, giỏ hàng, tóm tắt thanh toán, chi tiết đơn). `app.js` bắt sự kiện `error` ở pha
  capture và thay đúng ảnh đó; không dùng `onerror` inline và không ghi đè ảnh QR/avatar.
- **Neo trong trang:** header khách là `position: sticky` nên `theme.css` đặt
  `html { scroll-padding-top: 5rem }`. Đừng bỏ dòng này, nếu không `#chon-nhanh` và skip-link
  sẽ dừng ngay dưới đáy header.

## 8. Ngoài phạm vi (AI chỉ ĐỀ XUẤT, không tự làm vì cần sửa Java/DB)
Thêm giỏ hàng bằng AJAX, gợi ý tìm kiếm tự động, lưu danh sách yêu thích, lọc không tải lại trang.
