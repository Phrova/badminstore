/*
 * BadStore – hành vi UX dùng chung (nạp bằng th:src="@{/js/app.js}" defer).
 *
 * Phân chia trách nhiệm với main.js (nạp TRƯỚC app.js trong cả 2 layout):
 *   - main.js : xác nhận trước khi ghi CSDL (data-confirm), toast flash, Swiper, AOS,
 *               bộ chọn số lượng .js-quantity (giỏ hàng, trang chi tiết), GHN, đơn thủ công…
 *   - app.js  : khoá nút khi submit, window.toast(), nút lên đầu trang, dự phòng khi AOS lỗi.
 * Hai script KHÔNG còn trùng việc xác nhận/AOS nên không sợ hộp thoại hiện 2 lần.
 */
(function () {
  'use strict';

  // 1) Nút loading khi submit (tránh bấm đúp). Bỏ qua bằng data-no-loading.
  //    Nút có thuộc tính name không bị disable để không mất dữ liệu gửi lên.
  document.addEventListener('submit', function (e) {
    if (e.defaultPrevented) return;
    // Ưu tiên đúng nút đã bấm (form có thể có nhiều nút submit như "Thêm vào giỏ" / "Mua ngay").
    var b = e.submitter || e.target.querySelector('[type=submit]');
    if (!b || b.hasAttribute('data-no-loading')) return;
    setTimeout(function () {
      b.dataset.old = b.innerHTML;
      if (!b.name) { b.disabled = true; }
      b.innerHTML = '<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>Đang xử lý...';
    }, 0);
  });
  window.addEventListener('pageshow', function (ev) {   // quay lại bằng nút Back
    if (!ev.persisted) return;
    document.querySelectorAll('[type=submit][data-old]').forEach(function (b) { b.disabled = false; b.innerHTML = b.dataset.old; });
    document.querySelectorAll('form[data-confirmed]').forEach(function (f) { delete f.dataset.confirmed; });
  });

  // 2) Toast dùng chung:  toast('Đã thêm vào giỏ', 'success')
  //    main.js nạp trước đã có window.badToast (cùng kiểu toast, có xếp hàng + tạm dừng khi rê chuột).
  window.toast = function (title, icon) {
    if (window.badToast) {
      window.badToast(icon || 'success', title);
      return;
    }
    Swal.fire({ toast: true, position: 'top-end', icon: icon || 'success', title: title, showConfirmButton: false, showCloseButton: true, closeButtonAriaLabel: 'Đóng thông báo', timer: 2200, timerProgressBar: true });
  };

  // 3) Nút lên đầu trang:  <button id="backToTop" class="btn btn-primary rounded-circle d-none" aria-label="Lên đầu trang">
  var reduceMotion = window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var up = document.getElementById('backToTop');
  if (up) {
    window.addEventListener('scroll', function () { up.classList.toggle('d-none', window.scrollY < 400); }, { passive: true });
    up.addEventListener('click', function () {
      // Tôn trọng "giảm chuyển động": nhảy thẳng thay vì cuộn mượt.
      window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' });
    });
  }

  // 4) Ảnh sản phẩm hỏng (file trong uploads/ bị xoá, restore DB…): thay bằng ảnh mặc định.
  //    Chỉ áp dụng cho <img data-fallback="…"> để không ghi đè ảnh khác (QR, avatar…).
  //    Sự kiện error không bubble nên phải bắt ở pha capture.
  document.addEventListener('error', function (event) {
    var img = event.target;
    if (!img || img.tagName !== 'IMG' || !img.hasAttribute('data-fallback')) return;
    if (img.dataset.fallbackApplied) return;
    img.dataset.fallbackApplied = 'true';
    img.src = img.getAttribute('data-fallback');
  }, true);

  // 5) Biểu mẫu trả về lỗi (server render lại): đưa focus về ô sai đầu tiên để người dùng
  //    bàn phím/trình đọc màn hình biết ngay cần sửa gì, không phải dò lại cả trang.
  //    preventScroll + scrollIntoView để không nhảy cóc khi trang còn ảnh đang tải.
  function focusFirstInvalidField() {
    var invalid = document.querySelector('.is-invalid, [aria-invalid="true"]');
    if (!invalid || typeof invalid.focus !== 'function') return;
    invalid.focus({ preventScroll: true });
    if (typeof invalid.scrollIntoView === 'function') {
      invalid.scrollIntoView({ block: 'center', behavior: 'auto' });
    }
  }

  // 6) AOS lỗi/mất mạng: aos.css đặt opacity:0 cho [data-aos] nên trang sẽ trắng.
  //    Gắn .no-aos để theme.css hiện nội dung ngay. AOS.init do main.js gọi.
  if (!window.AOS) {
    document.documentElement.classList.add('no-aos');
  }

  // 7) Phím tắt "/" đưa focus vào ô tìm kiếm trên header (gợi ý hiện trong .search-hint).
  //    Bỏ qua khi đang gõ trong ô nhập để không cướp ký tự "/".
  document.addEventListener('keydown', function (event) {
    if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
    var target = event.target;
    var tagName = target && target.tagName ? target.tagName.toLowerCase() : '';
    if (tagName === 'input' || tagName === 'textarea' || tagName === 'select'
        || (target && target.isContentEditable)) return;
    var search = document.getElementById('navKeyword');
    if (!search) return;
    event.preventDefault();
    search.focus();
    search.select();
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', focusFirstInvalidField);
  } else {
    focusFirstInvalidField();
  }
})();

/* Nút sao chép (khối chuyển khoản QR): <button class="btn-copy" data-copy="giá trị"> */
document.addEventListener('click', function (event) {
    var button = event.target.closest ? event.target.closest('.btn-copy') : null;
    if (!button) {
        return;
    }
    var text = button.getAttribute('data-copy') || '';
    var done = function () {
        var icon = button.querySelector('i');
        if (icon) {
            icon.className = 'bi bi-clipboard-check text-success';
            setTimeout(function () { icon.className = 'bi bi-clipboard'; }, 1500);
        }
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done);
    } else {
        var area = document.createElement('textarea');
        area.value = text;
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy'); done(); } catch (e) { /* bỏ qua */ }
        document.body.removeChild(area);
    }
});
