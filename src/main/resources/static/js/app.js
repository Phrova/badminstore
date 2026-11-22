/*
 * BadStore – hành vi UX dùng chung (nạp bằng th:src="@{/js/app.js}" defer).
 * main.js nạp TRƯỚC: data-confirm, toast flash (badToast), Swiper, AOS, GHN…
 * app.js: khoá nút submit, window.toast() chống lặp, backToTop, ảnh dự phòng,
 *         phím "/", kiểm tra định dạng CHỈ khi submit (không toast khi rời ô).
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
      // Kiểm tra LẠI: handler chạy sau (validate, xác nhận…) có thể đã chặn submit.
      if (e.defaultPrevented || !b.isConnected || b.dataset.old) return;
      b.dataset.old = b.innerHTML;
      // Nút có name: giữ giá trị bằng input ẩn rồi mới disable → không mất dữ liệu.
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
  var lastToast = { key: '', at: 0 };
  window.toast = function (title, icon) {
    icon = icon || 'success';
    var key = icon + '|' + title;
    var now = Date.now();
    if (key === lastToast.key && now - lastToast.at < 2500) return;
    lastToast = { key: key, at: now };
    if (window.badToast) { window.badToast(icon, title); return; }
    if (!window.Swal) return;
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

  // 8) Kiểm tra định dạng CHỈ khi submit: email, SĐT, số lượng, mật khẩu, mã giảm giá.
  var EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  var PHONE_RE = /^(0|\+84)\d{9,10}$/;

  function baoLoi(input, message) {
    input.classList.add('is-invalid');
    input.setAttribute('aria-invalid', 'true');
    window.toast(message, 'warning');
    input.focus();
  }

  function loiSoLuong(input) {
    var v = (input.value || '').trim();
    if (!v) return input.required ? 'Vui lòng nhập số lượng' : '';
    if (!/^\d+$/.test(v)) return 'Số lượng phải là số nguyên dương';
    var n = Number(v);
    if (input.min !== '' && n < Number(input.min)) return 'Số lượng tối thiểu là ' + input.min;
    if (input.max !== '' && n > Number(input.max)) return 'Số lượng tối đa là ' + input.max + ' (theo tồn kho)';
    return '';
  }

  function loiDinhDang(input) {
    if (input.type === 'number') return loiSoLuong(input);
    var v = (input.value || '').trim();
    if (input.name === 'voucher') return (!v && input.required) ? 'Vui lòng nhập mã giảm giá' : '';
    if (input.type === 'password') {
      if (!v) return input.required ? 'Vui lòng nhập mật khẩu' : '';
      if (input.minLength > 0 && v.length < input.minLength) return 'Mật khẩu phải từ ' + input.minLength + ' ký tự';
      if (input.maxLength > 0 && v.length > input.maxLength) return 'Mật khẩu tối đa ' + input.maxLength + ' ký tự';
      return '';
    }
    if (!v) return '';
    if (input.type === 'email' && !EMAIL_RE.test(v)) {
      return 'Email chưa đúng định dạng (ví dụ: ten@mien.com)';
    }
    if (input.type === 'tel') {
      var re = PHONE_RE;
      if (input.pattern) {
        try { re = new RegExp('^(?:' + input.pattern + ')$'); } catch (err) { /* giữ PHONE_RE */ }
      }
      if (!re.test(v)) return 'Số điện thoại chưa đúng (ví dụ: 0912345678)';
    }
    return '';
  }

  function laOCanKiemTra(o) {
    return o && o.matches && (o.matches('input[type="email"]') || o.matches('input[type="tel"]')
      || o.matches('input[type="number"]') || o.matches('input[name="voucher"]')
      || o.matches('input[type="password"]'));
  }

  document.addEventListener('focusout', function (e) {
    var o = e.target;
    // Chỉ email/SĐT được cắt khoảng trắng; mật khẩu giữ nguyên từng ký tự.
    if (o && o.matches && (o.matches('input[type="email"]') || o.matches('input[type="tel"]'))) {
      o.value = o.value.trim();
    }
  });

  document.addEventListener('input', function (e) {
    if (!laOCanKiemTra(e.target)) return;
    e.target.classList.remove('is-invalid');
    e.target.removeAttribute('aria-invalid');
  });

  document.addEventListener('submit', function (e) {
    var form = e.target;
    // Mã giảm giá: chuẩn hoá trim + HOA trước khi gửi.
    var vouchers = form.querySelectorAll ? form.querySelectorAll('input[name="voucher"]') : [];
    for (var k = 0; k < vouchers.length; k++) {
      vouchers[k].value = vouchers[k].value.trim().toUpperCase();
    }
    var inputs = form.querySelectorAll
      ? form.querySelectorAll('input[type="email"], input[type="tel"], input[type="number"], input[name="voucher"], input[type="password"]')
      : [];
    for (var i = 0; i < inputs.length; i++) {
      var msg = loiDinhDang(inputs[i]);
      if (msg) {
        e.preventDefault();
        baoLoi(inputs[i], msg);
        return;
      }
    }
    // Cặp mật khẩu: đăng ký (password) và đổi mật khẩu (newPassword).
    var pw = form.querySelector ? form.querySelector('input[name="password"], input[name="newPassword"]') : null;
    var cf = form.querySelector ? form.querySelector('input[name="confirmPassword"]') : null;
    if (pw && cf && cf.value !== pw.value) {
      e.preventDefault();
      baoLoi(cf, 'Mật khẩu xác nhận không khớp');
      return;
    }
    // Mật khẩu mới phải khác mật khẩu hiện tại (server cũng kiểm tra lại).
    var cur = form.querySelector ? form.querySelector('input[name="currentPassword"]') : null;
    var npw = form.querySelector ? form.querySelector('input[name="newPassword"]') : null;
    if (cur && npw && npw.value && npw.value === cur.value) {
      e.preventDefault();
      baoLoi(npw, 'Mật khẩu mới phải khác mật khẩu hiện tại');
    }
  });

  // Form KHÔNG novalidate: trình duyệt chặn trước submit → thay bong bóng bằng 1 toast.
  document.addEventListener('invalid', function (e) {
    var o = e.target;
    if (!laOCanKiemTra(o)) return;
    var msg = '';
    if (o.type === 'number') {
      var v = o.validity;
      if (v.valueMissing) msg = 'Vui lòng nhập số lượng';
      else if (v.rangeUnderflow) msg = 'Số lượng tối thiểu là ' + o.min;
      else if (v.rangeOverflow) msg = 'Số lượng tối đa là ' + o.max + ' (theo tồn kho)';
      else if (v.stepMismatch || v.badInput) msg = 'Số lượng phải là số nguyên dương';
    } else if (o.type === 'password') {
      var p = o.validity;
      if (p.valueMissing) msg = 'Vui lòng nhập mật khẩu';
      else if (p.tooShort) msg = 'Mật khẩu phải từ ' + o.minLength + ' ký tự';
      else if (p.tooLong) msg = 'Mật khẩu tối đa ' + o.maxLength + ' ký tự';
    } else {
      if (!o.value.trim() && o.name !== 'voucher') return;
      msg = loiDinhDang(o);
    }
    if (!msg) return;
    e.preventDefault();
    baoLoi(o, msg);
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

  // 10) Nav Trang chủ / Sản phẩm: tô mục theo đường dẫn (so đúng ranh giới '/').
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