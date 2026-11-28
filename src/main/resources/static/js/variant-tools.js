/* variant-tools.js — gõ tắt tiền (1m/500k), chip gợi ý nhanh (giá/tồn kho/màu/tên SP),
   tự viết hoa chữ đầu mỗi từ, hint dạng popup khi hover/focus.
   Chỉ tác động lên input có data-money / data-quick / data-capitalize / data-hint. */
(function () {
    'use strict';

    var PRICE_PRESETS = [{ label: '100k', value: 100000 }, { label: '1m', value: 1000000 }, { label: '10m', value: 10000000 }];
    var STOCK_PRESETS = [{ label: '5', value: 5 }, { label: '20', value: 20 }, { label: '50', value: 50 }];
     var COLOR_PRESETS = ['Đen', 'Đỏ', 'Trắng'];
    var ISSUE_PRESETS = [{ label: '10', value: 10 }, { label: '20', value: 20 }, { label: '50', value: 50 }];
    var PERCENT_PRESETS = [{ label: '10%', value: 10 }, { label: '15%', value: 15 }, { label: '20%', value: 20 }];
    var ORDER_PRESETS = [{ label: '100k', value: 100000 }, { label: '1tr', value: 1000000 }, { label: '5tr', value: 5000000 }];
    var NAME_PRESETS = ['Vợt cầu lông Yonex', 'Vợt cầu lông Lining', 'Vợt cầu lông Victor'];

    function toast(kind, msg) { if (typeof window.badToast === 'function') { window.badToast(kind, msg); } }

       function parseMoney(raw, keepDecimals) {
        var s = String(raw == null ? '' : raw).trim().toLowerCase().replace(/\s+/g, '');
        if (!s) { return NaN; }
        s = s.replace(/%$/, '');
        if (/^\d{1,3}(\.\d{3})+$/.test(s)) { s = s.replace(/\./g, ''); }
        s = s.replace(',', '.');
        var m = s.match(/^(\d+(?:\.\d+)?)(m|tr|trieu|triệu|k|nghin|nghìn|ngan|ngàn)?$/);
        if (!m) { return NaN; }
        var n = parseFloat(m[1]);
        var u = m[2];
        if (u === 'm' || u === 'tr' || u === 'trieu' || u === 'triệu') { n *= 1e6; }
        else if (u) { n *= 1e3; }
        /* Phần trăm cần giữ số lẻ (10.5% không được làm tròn thành 11%). */
        return keepDecimals ? Math.round(n * 100) / 100 : Math.round(n);
    }
    window.VG_parseMoney = parseMoney;

    /* Viết hoa chữ cái ĐẦU mỗi từ, giữ nguyên phần còn lại (ZZ, 3U, 100ZZ không bị đổi). */
    function capitalizeWords(s) {
        return String(s).trim().replace(/\s+/g, ' ').split(' ').map(function (w) {
            return w ? w.charAt(0).toUpperCase() + w.slice(1) : w;
        }).join(' ');
    }
    window.VG_capitalize = capitalizeWords;

    function bindMoney(input) {
        if (input.dataset.moneyBound) { return; }
        input.dataset.moneyBound = '1';
        function normalize() {
            var raw = input.value;
            if (!String(raw).trim()) { return; }
                     var n = parseMoney(raw, input.hasAttribute('data-decimal'));
            if (isNaN(n)) { toast('warning', 'Chưa hiểu giá "' + raw + '" – thử 1m, 500k hoặc 1000000.'); return; }
            if (String(n) !== String(raw)) {
                input.value = String(n);
                input.dispatchEvent(new Event('input', { bubbles: true }));
            }
        }
        input.addEventListener('blur', normalize);
        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); normalize(); }
        });
    }

    var pop = null, popFor = null, hideTimer = null;
    function presetsFor(kind) {
          if (kind === 'price') { return PRICE_PRESETS; }
        if (kind === 'stock') { return STOCK_PRESETS; }
        if (kind === 'issue') { return ISSUE_PRESETS; }
        if (kind === 'percent') { return PERCENT_PRESETS; }
        if (kind === 'order') { return ORDER_PRESETS; }
        if (kind === 'name') { return NAME_PRESETS.map(function (c) { return { label: c, value: c }; }); }
        return COLOR_PRESETS.map(function (c) { return { label: c, value: c }; });
    }
    function hidePop() { if (pop) { pop.remove(); pop = null; popFor = null; } }
    function showPop(input) {
        if (popFor === input) { return; }
        var hint = input.getAttribute('data-hint');
        var kind = input.getAttribute('data-quick');
        if (!hint && !kind) { return; }
        hidePop();
        pop = document.createElement('div');
        if (hint) {
            pop.className = 'vg-hint';
            pop.textContent = hint;
        } else {
            pop.className = 'vg-quick';
            presetsFor(kind).forEach(function (p) {
                var b = document.createElement('button');
                b.type = 'button';
                b.textContent = p.label;
                b.addEventListener('pointerdown', function (e) { e.preventDefault(); });
                b.addEventListener('click', function () {
                    input.value = String(p.value);
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    input.focus();
                });
                pop.appendChild(b);
            });
        }
        document.body.appendChild(pop);
        var r = input.getBoundingClientRect();
        var left = Math.max(8, Math.min(r.left, window.innerWidth - pop.offsetWidth - 8));
        var top = r.bottom + 6;
        if (top + pop.offsetHeight > window.innerHeight - 8) { top = r.top - pop.offsetHeight - 6; }
        pop.style.left = left + 'px';
        pop.style.top = Math.max(8, top) + 'px';
        popFor = input;
    }
    function scheduleHide() { clearTimeout(hideTimer); hideTimer = setTimeout(hidePop, 140); }
    function isPopTarget(el) { return el && el.matches && el.matches('[data-quick],[data-hint]'); }

    document.addEventListener('focusin', function (e) {
        var el = e.target;
        if (!el.matches) { return; }
        if (el.matches('[data-money]')) { bindMoney(el); }
        if (isPopTarget(el)) { clearTimeout(hideTimer); showPop(el); }
    });
    document.addEventListener('focusout', function (e) {
        var el = e.target;
        if (!el.matches) { return; }
        if (isPopTarget(el)) { scheduleHide(); }
        if (el.matches('[data-capitalize]') && el.value.trim()) {
            var v = capitalizeWords(el.value);
            if (v !== el.value) {
                el.value = v;
                el.dispatchEvent(new Event('input', { bubbles: true }));
            }
        }
    });
    document.addEventListener('mouseover', function (e) {
        if (!e.target.closest) { return; }
        if (e.target.closest('.vg-quick') || e.target.closest('.vg-hint')) { clearTimeout(hideTimer); return; }
        var el = e.target.closest('[data-quick],[data-hint]');
        if (el) { clearTimeout(hideTimer); showPop(el); }
        else if (pop) { scheduleHide(); }
    });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') { hidePop(); } });
    window.addEventListener('scroll', hidePop, true);
    window.addEventListener('resize', hidePop);

    document.querySelectorAll('[data-money]').forEach(bindMoney);
})();