/*
 * BadStore – hành vi UX dùng chung (nạp defer, sau main.js).
 * app.js: khoá nút submit, toast chống lặp, backToTop, ảnh dự phòng, phím "/",
 * kiểm tra dữ liệu ở pha CAPTURE (chạy TRƯỚC hộp xác nhận của main.js),
 * chặn email gõ nhầm tên miền (gmail.col, yahoo.con, gmial.com…).
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
      if (e.defaultPrevented || !b.isConnected || b.dataset.old) return;
      b.dataset.old = b.innerHTML;
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

  window.addEventListener('pageshow', function (ev) {
    if (!ev.persisted) return;
    document.querySelectorAll('[data-loading-proxy]').forEach(function (h) { h.remove(); });
    document.querySelectorAll('[type=submit][data-old]').forEach(function (b) {
      b.disabled = false;
      b.innerHTML = b.dataset.old;
      delete b.dataset.old;
    });
    document.querySelectorAll('form[data-confirmed]').forEach(function (f) { delete f.dataset.confirmed; });
    document.querySelectorAll('form[data-voucher-checked]').forEach(function (f) { delete f.dataset.voucherChecked; });
  });

  // 2) Toast dùng chung + chống lặp.
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

  // 3) Nút lên đầu trang.
  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var up = document.getElementById('backToTop');
  if (up) {
    window.addEventListener('scroll', function () { up.classList.toggle('d-none', window.scrollY < 400); }, { passive: true });
    up.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' });
    });
  }

  // 4) Ảnh hỏng → ảnh mặc định.
  document.addEventListener('error', function (event) {
    var img = event.target;
    if (!img || img.tagName !== 'IMG' || !img.hasAttribute('data-fallback')) return;
    if (img.dataset.fallbackApplied) return;
    img.dataset.fallbackApplied = 'true';
    img.src = img.getAttribute('data-fallback');
  }, true);

  // 5) Focus ô sai đầu tiên khi server render lại form.
  function focusFirstInvalidField() {
    var invalid = document.querySelector('.is-invalid, [aria-invalid="true"]');
    if (!invalid || typeof invalid.focus !== 'function') return;
    invalid.focus({ preventScroll: true });
    if (typeof invalid.scrollIntoView === 'function') invalid.scrollIntoView({ block: 'center', behavior: 'auto' });
  }

  // 6) AOS lỗi → hiện nội dung ngay.
  if (!window.AOS) document.documentElement.classList.add('no-aos');

  // 7) Phím "/" → ô tìm kiếm.
  document.addEventListener('keydown', function (event) {
    if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey) return;
    var t = event.target;
    var tag = t && t.tagName ? t.tagName.toLowerCase() : '';
    if (tag === 'input' || tag === 'textarea' || tag === 'select' || (t && t.isContentEditable)) return;
    var search = document.getElementById('navKeyword');
    if (!search) return;
    event.preventDefault();
    search.focus();
    search.select();
  });

  // 8) Bộ kiểm tra dữ liệu.
  var EMAIL_RE = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/;
  var PHONE_RE = /^(0|\+84)\d{9,10}$/;
  var GMAIL_OK = { 'gmail.com': true, 'googlemail.com': true };
  // Đuôi không tồn tại thật → chắn cứng, kèm gợi ý sửa.
  var TLD_FIX = {
    col: 'com', con: 'com', cmo: 'com', comm: 'com', coom: 'com', vom: 'com', xom: 'com',
    om: 'com', cm: 'com', cim: 'com', cpm: 'com', clm: 'com', comv: 'com', comn: 'com', conm: 'com',
    ner: 'net', met: 'net', nrt: 'net', orgg: 'org', orh: 'org', vnn: 'vn'
  };
  // Tên miền gõ nhầm nhưng đuôi CÓ thật → cảnh báo mềm, cho đi tiếp ở lần bấm thứ hai.
  var DOMAIN_FIX = {
    'gmial.com': 'gmail.com', 'gmai.com': 'gmail.com', 'gmal.com': 'gmail.com', 'gamil.com': 'gmail.com',
    'gnail.com': 'gmail.com', 'gmail.co': 'gmail.com', 'gmail.cm': 'gmail.com', 'gmail.om': 'gmail.com',
    'yaho.com': 'yahoo.com', 'hotmial.com': 'hotmail.com', 'hotmai.com': 'hotmail.com',
    'outlok.com': 'outlook.com', 'iclod.com': 'icloud.com'
  };

  function nhan(input) {
    if (input.id) {
      var lab = document.querySelector('label[for="' + input.id.replace(/"/g, '') + '"]');
      if (lab) {
        var t = (lab.textContent || '').replace(/\*/g, '').trim();
        if (t && t.length <= 40) return t;
      }
    }
    var al = input.getAttribute('aria-label');
    if (al) return al;
    if (input.type === 'radio' || input.type === 'checkbox') {
      var card = input.closest('.card');
      var head = card ? card.querySelector('.card-header') : null;
      if (head) return (head.textContent || '').trim();
      var fs = input.closest('fieldset');
      var lg = fs ? fs.querySelector('legend') : null;
      if (lg) return (lg.textContent || '').trim();
      return '';
    }
    return input.getAttribute('placeholder') || '';
  }

  function thongBaoLoi(input) {
    var v = input.validity;
    var ten = nhan(input);
    var chon = input.tagName === 'SELECT' || input.type === 'radio' || input.type === 'checkbox';
    if (v.valueMissing) {
      if (input.type === 'email') return 'Vui lòng nhập email';
      if (input.type === 'tel') return 'Vui lòng nhập số điện thoại';
      if (input.type === 'password') return 'Vui lòng nhập mật khẩu';
      if (input.name === 'voucher') return 'Vui lòng nhập mã giảm giá';
      if (input.type === 'number') return 'Vui lòng nhập số lượng';
      if (ten) return chon ? 'Vui lòng chọn ' + ten : 'Vui lòng nhập ' + ten;
      return 'Vui lòng điền thông tin bắt buộc';
    }
    if (v.typeMismatch && input.type === 'email') return 'Email chưa đúng định dạng (ví dụ: ten@mien.com)';
    if (v.patternMismatch) {
      return input.type === 'tel' ? 'Số điện thoại chưa đúng (ví dụ: 0912345678)'
        : (ten ? ten + ' chưa đúng định dạng' : 'Giá trị chưa đúng định dạng');
    }
    if (v.tooShort) return (ten || 'Giá trị') + ' phải từ ' + input.minLength + ' ký tự';
    if (v.tooLong) return (ten || 'Giá trị') + ' tối đa ' + input.maxLength + ' ký tự';
    if (v.rangeUnderflow) return (ten || 'Giá trị') + ' tối thiểu là ' + input.min;
    if (v.rangeOverflow) return (ten || 'Giá trị') + ' tối đa là ' + input.max;
    if (v.stepMismatch) return input.name === 'quantity' ? 'Số lượng phải là số nguyên dương' : (ten || 'Giá trị') + ' không hợp lệ';
    if (v.badInput) return 'Vui lòng nhập số hợp lệ';
    if (v.customError) return input.validationMessage || 'Giá trị không hợp lệ';
    return '';
  }

  function emailIssue(raw) {
    var v = (raw || '').trim();
    if (!v) return null;
    if (v.length > 100) return { level: 'block', message: 'Email tối đa 100 ký tự' };
    var at = v.lastIndexOf('@');
    if (at <= 0) return { level: 'block', message: 'Email chưa đúng định dạng (ví dụ: ten@mien.com)' };
    var local = v.slice(0, at);
    var domain = v.slice(at + 1).toLowerCase();
    if (!domain || local.length > 64 || domain.length > 255) {
      return { level: 'block', message: 'Email chưa đúng định dạng (ví dụ: ten@mien.com)' };
    }
    if (local.charAt(0) === '.' || local.charAt(local.length - 1) === '.' || local.indexOf('..') !== -1) {
      return { level: 'block', message: 'Email chưa đúng định dạng (ví dụ: ten@mien.com)' };
    }
    if (!EMAIL_RE.test(v)) return { level: 'block', message: 'Email chưa đúng định dạng (ví dụ: ten@mien.com)' };
    if (domain.indexOf('gmail.') === 0 && !GMAIL_OK[domain]) {
      return { level: 'block', message: 'Gmail chỉ dùng tên miền gmail.com', suggestion: local + '@gmail.com' };
    }
    var cut = domain.lastIndexOf('.');
    var tld = domain.slice(cut + 1);
    if (TLD_FIX[tld]) {
      return {
        level: 'block',
        message: 'Tên miền ".' + tld + '" không tồn tại',
        suggestion: local + '@' + domain.slice(0, cut + 1) + TLD_FIX[tld]
      };
    }
    if (DOMAIN_FIX[domain]) {
      return { level: 'warn', message: 'Email có thể gõ nhầm tên miền', suggestion: local + '@' + DOMAIN_FIX[domain] };
    }
    return null;
  }

  function clearSuggest(input) {
    if (input._emailSuggest && input._emailSuggest.parentNode) input._emailSuggest.parentNode.removeChild(input._emailSuggest);
    input._emailSuggest = null;
  }

  function showSuggest(input, message, suggestion) {
    clearSuggest(input);
    var wrap = document.createElement('div');
    wrap.className = 'email-suggest';
    wrap.id = 'emailSuggest-' + (input.id || Math.random().toString(36).slice(2));
    wrap.setAttribute('role', 'status');
    var icon = document.createElement('i');
    icon.className = 'bi bi-lightbulb';
    icon.setAttribute('aria-hidden', 'true');
    var text = document.createElement('span');
    text.textContent = message + '. Ý bạn là ';
    var strong = document.createElement('strong');
    strong.textContent = suggestion;
    text.appendChild(strong);
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-sm btn-glass btn-glass--primary';
    btn.textContent = 'Dùng email này';
    btn.addEventListener('click', function () {
      input.value = suggestion;
      input.classList.remove('is-invalid');
      input.removeAttribute('aria-invalid');
      delete input.dataset.emailWarned;
      clearSuggest(input);
      input.focus();
      window.toast('Đã sửa email thành ' + suggestion, 'success');
    });
    wrap.appendChild(icon);
    wrap.appendChild(text);
    wrap.appendChild(btn);
    var anchor = input.closest('.input-group') || input;
    anchor.parentNode.insertBefore(wrap, anchor.nextSibling);
    input._emailSuggest = wrap;
    input.setAttribute('aria-describedby', ((input.getAttribute('aria-describedby') || '') + ' ' + wrap.id).trim());
  }

  function baoLoi(input, message) {
    input.classList.add('is-invalid');
    input.setAttribute('aria-invalid', 'true');
    window.toast(message, 'warning');
    input.focus();
  }

  function kiemTraForm(form) {
    var controls = form.querySelectorAll('input, select, textarea');
    for (var i = 0; i < controls.length; i++) {
      var el = controls[i];
      if (!el.willValidate) continue;
      if (el.type === 'email') {
        var val = (el.value || '').trim();
        var at = val.lastIndexOf('@');
        el.value = at > 0 ? val.slice(0, at) + '@' + val.slice(at + 1).toLowerCase() : val;
        var issue = emailIssue(el.value);
        if (issue) {
          if (issue.level === 'warn') {
            if (el.dataset.emailWarned !== el.value) {
              el.dataset.emailWarned = el.value;
              return { input: el, msg: issue.message + ' – bấm gửi lần nữa nếu đúng', issue: issue };
            }
          } else {
            return { input: el, msg: issue.message, issue: issue };
          }
        }
      }
      if (!el.validity.valid) {
        var m = thongBaoLoi(el);
        if (m) return { input: el, msg: m };
      }
    }
    var pw = form.querySelector('input[name="password"], input[name="newPassword"]');
    var cf = form.querySelector('input[name="confirmPassword"]');
    if (pw && cf && cf.value !== pw.value) return { input: cf, msg: 'Mật khẩu xác nhận không khớp' };
    var cur = form.querySelector('input[name="currentPassword"]');
    var npw = form.querySelector('input[name="newPassword"]');
    if (cur && npw && npw.value && npw.value === cur.value) {
      return { input: npw, msg: 'Mật khẩu mới phải khác mật khẩu hiện tại' };
    }
    return null;
  }

  document.addEventListener('focusout', function (e) {
    var o = e.target;
    if (o && o.matches && (o.matches('input[type="email"]') || o.matches('input[type="tel"]'))) o.value = o.value.trim();
  });

  function xoaDauVet(el) {
    if (!el || !el.matches || !el.matches('input, select, textarea')) return;
    el.classList.remove('is-invalid');
    el.removeAttribute('aria-invalid');
    if (el.type === 'email') { delete el.dataset.emailWarned; clearSuggest(el); }
  }
  document.addEventListener('input', function (e) { xoaDauVet(e.target); });
  document.addEventListener('change', function (e) { xoaDauVet(e.target); });

  // Pha CAPTURE + stopImmediatePropagation: chặn TRƯỚC hộp xác nhận của main.js,
  // nếu không main.js mở hộp thoại rồi form.submit() bỏ qua mọi kiểm tra.
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.tagName !== 'FORM') return;
    var bad = kiemTraForm(form);
    if (!bad) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    baoLoi(bad.input, bad.msg);
    if (bad.issue && bad.issue.suggestion) showSuggest(bad.input, bad.msg, bad.issue.suggestion);
  }, true);

  // Form không novalidate: trình duyệt chặn trước submit → thay bong bóng bằng toast tiếng Việt.
  document.addEventListener('invalid', function (e) {
    var el = e.target;
    if (!el || !el.willValidate) return;
    var msg = thongBaoLoi(el);
    var issue = el.type === 'email' ? emailIssue(el.value) : null;
    if (issue && issue.level === 'block') msg = issue.message;
    if (!msg) return;
    e.preventDefault();
    baoLoi(el, msg);
    if (issue && issue.suggestion) showSuggest(el, issue.message, issue.suggestion);
  }, true);

  // 9) Mã giảm giá: chuẩn hoá + preflight để không rơi vào trang lỗi 500.
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.tagName !== 'FORM') return;
    var vouchers = form.querySelectorAll('input[name="voucher"]');
    for (var k = 0; k < vouchers.length; k++) vouchers[k].value = vouchers[k].value.trim().toUpperCase();
    if (form.dataset.voucherChecked === '1') return;
    if (!form.matches('form[method="get"]')) return;
    var input = form.querySelector('input[name="voucher"]');
    if (!input) return;
    var action = form.getAttribute('action') || '';
    if (action.indexOf('/thanh-toan') === -1) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    var code = input.value.trim().toUpperCase();
    input.value = code;
    var go = function () { form.dataset.voucherChecked = '1'; form.submit(); };
    if (!code || !window.fetch) { go(); return; }
    var url = action + (action.indexOf('?') === -1 ? '?' : '&') + 'voucher=' + encodeURIComponent(code);
    fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } })
      .then(function (res) {
        if (res.ok) { go(); return; }
        window.toast('Mã giảm giá không tồn tại hoặc không dùng được.', 'error');
        input.classList.add('is-invalid');
        input.setAttribute('aria-invalid', 'true');
        input.focus();
      })
      .catch(function () { go(); });
  }, true);

  // 10) Nút sao chép QR.
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
    var fail = function () { window.toast('Không sao chép được, hãy bôi đen và copy thủ công', 'warning'); };
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

  // 11) Nav Trang chủ / Sản phẩm.
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