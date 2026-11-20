/*
 * BadStore – hành vi UX dùng chung (nạp bằng th:src="@{/js/app.js}" defer).
 * main.js nạp TRƯỚC: data-confirm, toast flash (badToast), Swiper, AOS, GHN…
 * app.js: khoá nút submit, window.toast() chống lặp, backToTop, ảnh dự phòng,
 *         phím "/", email CHỈ kiểm tra khi submit (không toast khi rời ô).
 */
(function () {
  'use strict';

  // 1) Khoá nút khi submit (chống bấm đúp). Bỏ qua bằng data-no-loading.
  document.addEventListener('submit', function (e) {
    if (e.defaultPrevented) return;
    var form = e.target;
    var b = e.submitter || form.querySelector('[type=submit]');
    if (!b || b.hasAttribute('data-no-loading')) return;
    setTimeout(function () {
      // Kiểm tra LẠI tại đây: handler chạy sau (email sai, xác nhận…) có thể đã chặn submit.
      // Không kiểm tra lại → nút kẹt "Đang xử lý..." mãi mãi khi form bị chặn.
      if (e.defaultPrevented || !b.isConnected || b.dataset.old) return;
      b.dataset.old = b.innerHTML;
      // Nút có name: tạo input ẩn giữ đúng giá trị rồi mới disable → không mất dữ liệu,
      // và nút vẫn bị khoá thật (trước đây nút có name không khoá → bấm đúp gửi 2 lần).
      if (b.name) {
        var h = document.createElement('input');
        h.type = 'hidden';
        h.name = b.name;
        h.value = b.value;
        h.setAttribute('data-loading-proxy', '');
        form.appendChild(h);
      }
      b.disabled = true;
      b.innerHTML = '<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>Đang xử lý...';
    }, 0);
  });

  // Quay lại bằng nút Back (bfcache): khôi phục nút, xoá input ẩn tạm, xoá cờ xác nhận.
  window.addEventListener('pageshow', function (ev) {
    if (!ev.persisted) return;
    document.querySelectorAll('[data-loading-proxy]').forEach(function (h) { h.remove(); });
    document.querySelectorAll('[type=submit][data-old]').forEach(function (b) {
      b.disabled = false;
      b.innerHTML = b.dataset.old;
      delete b.dataset.old;
    });
    document.querySelectorAll('form[data-confirmed]').forEach(function (f) { delete f.dataset.confirmed; });
  });

  // 2) Toast dùng chung + CHỐNG LẶP: cùng nội dung trong 2.5s chỉ hiện 1 lần.
  //    Đây là chốt chặn cuối: dù ai gọi toast nhiều lần, khách chỉ thấy 1.
  var lastToast = { key: '', at: 0 };
  window.toast = function (title, icon) {
    icon = icon || 'success';
    var key = icon + '|' + title;
    var now = Date.now();
    if (key === lastToast.key && now - lastToast.at < 2500) return;
    lastToast = { key: key, at: now };
    if (window.badToast) { window.badToast(icon, title); return; }
    if (!window.Swal) return; // CDN lỗi: bỏ qua, không ném lỗi làm chết script khác
    Swal.fire({ toast: true, position: 'top-end', icon: icon, title: title, showConfirmButton: false, showCloseButton: true, closeButtonAriaLabel: 'Đóng thông báo', timer: 2200, timerProgressBar: true });
  };

  // 3) Nút lên đầu trang (tôn trọng "giảm chuyển động").
  var reduceMotion = window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var up = document.getElementById('backToTop');
  if (up) {
    window.addEventListener('scroll', function () { up.classList.toggle('d-none', window.scrollY < 400); }, { passive: true });
    up.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' });
    });
  }

  // 4) Ảnh sản phẩm hỏng → thay ảnh mặc định (chỉ <img data-fallback="…">).
  document.addEventListener('error', function (event) {
    var img = event.target;
    if (!img || img.tagName !== 'IMG' || !img.hasAttribute('data-fallback')) return;
    if (img.dataset.fallbackApplied) return;
    img.dataset.fallbackApplied = 'true';
    img.src = img.getAttribute('data-fallback');
  }, true);

  // 5) Form server trả về lỗi: focus ô sai đầu tiên.
  function focusFirstInvalidField() {
    var invalid = document.querySelector('.is-invalid, [aria-invalid="true"]');
    if (!invalid || typeof invalid.focus !== 'function') return;
    invalid.focus({ preventScroll: true });
    if (typeof invalid.scrollIntoView === 'function') {
      invalid.scrollIntoView({ block: 'center', behavior: 'auto' });
    }
  }

  // 6) AOS lỗi/mất mạng: hiện nội dung ngay, không để trang trắng.
  if (!window.AOS) {
    document.documentElement.classList.add('no-aos');
  }

  // 7) Phím "/" → focus ô tìm kiếm (bỏ qua khi đang gõ trong ô nhập).
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

  // 8) Email: CHỈ báo khi bấm submit. Rời ô thì lặng lẽ cắt khoảng trắng, KHÔNG toast.
  //    Tô đỏ ô sai (is-invalid) để khách biết sửa ở đâu; gõ lại thì xoá đỏ.
  var EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  function kiemTraEmail(input) {
    input.classList.add('is-invalid');
    input.setAttribute('aria-invalid', 'true');
    window.toast('Email chưa đúng định dạng (ví dụ: ten@mien.com)', 'warning');
    input.focus();
  }
  document.addEventListener('focusout', function (e) {
    var o = e.target;
    if (!o || !o.matches || !o.matches('input[type="email"]')) return;
    o.value = o.value.trim(); // sửa thầm, không thông báo
  });
  document.addEventListener('input', function (e) {
    var o = e.target;
    if (!o || !o.matches || !o.matches('input[type="email"]')) return;
    o.classList.remove('is-invalid');
    o.removeAttribute('aria-invalid');
  });
  document.addEventListener('submit', function (e) {
    var inputs = e.target.querySelectorAll ? e.target.querySelectorAll('input[type="email"]') : [];
    for (var i = 0; i < inputs.length; i++) {
      var o = inputs[i];
      if (o.value.trim() && !EMAIL_RE.test(o.value.trim())) {
        e.preventDefault();
        kiemTraEmail(o);
        return;
      }
    }
  });
  // Form KHÔNG có novalidate: trình duyệt tự chặn email sai TRƯỚC cả sự kiện submit.
  // Bắt sự kiện 'invalid' để thay bong bóng mặc định bằng đúng 1 toast của mình.
  document.addEventListener('invalid', function (e) {
    var o = e.target;
    if (!o || !o.matches || !o.matches('input[type="email"]')) return;
    if (!o.value.trim()) return; // ô trống: để required của trình duyệt xử lý
    e.preventDefault();
    kiemTraEmail(o);
  }, true);

  // 9) Nút sao chép (QR): báo lỗi thân thiện nếu không sao chép được.
  document.addEventListener('click', function (event) {
    var button = event.target.closest ? event.target.closest('.btn-copy') : null;
    if (!button) return;
    var text = button.getAttribute('data-copy') || '';
    if (!text) return;
    var done = function () {
      var icon = button.querySelector('i');
      if (icon) {
        icon.className = 'bi bi-clipboard-check text-success';
        setTimeout(function () { icon.className = 'bi bi-clipboard'; }, 1500);
      }
    };
    var fail = function () {
      window.toast('Không sao chép được, hãy bôi đen và copy thủ công', 'warning');
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fail);
    } else {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (err) { /* bỏ qua */ }
      document.body.removeChild(area);
      if (ok) { done(); } else { fail(); }
    }
  });

  // 10) Nav Trang chủ / Sản phẩm: tô mục theo đường dẫn.
  //     So đúng ranh giới '/' để "/san-pham" không tô nhầm cho "/san-pham-abc".
  var path = location.pathname.replace(/\/+$/, '') || '/';
  document.querySelectorAll('.nav-seg-opt').forEach(function (a) {
    var href = (a.getAttribute('href') || '').replace(/\/+$/, '') || '/';
    if (path === href || (href !== '/' && path.indexOf(href + '/') === 0)) {
      a.classList.add('active');
      a.setAttribute('aria-current', 'page');
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', focusFirstInvalidField);
  } else {
    focusFirstInvalidField();
  }
})();