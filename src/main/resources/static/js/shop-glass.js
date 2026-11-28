/* shop-glass.js — nâng cấp khu khách, không sửa template:
   1) Vương miện vàng cho ô TỐT HƠN ở bảng so sánh (.compare-table).
   2) Gợi ý phí giao hàng (checkout) -> nút mở/đóng "Phí giao hàng tính".
   Chạy sau DOM; an toàn nếu phần tử không tồn tại; không lặp listener. */
(function () {
    'use strict';

    var CROWN = '<span class="cmp-crown" title="{T}"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
        '<path d="M3 8l4.5 3.5L12 5l4.5 6.5L21 8l-1.6 9.2a1.5 1.5 0 0 1-1.48 1.3H6.08a1.5 1.5 0 0 1-1.48-1.3L3 8z" ' +
        'fill="#ffb703" stroke="#e5a400" stroke-width="1.2" stroke-linejoin="round"/>' +
        '<circle cx="3" cy="7" r="1.6" fill="#ffd166"/><circle cx="21" cy="7" r="1.6" fill="#ffd166"/>' +
        '<circle cx="12" cy="4" r="1.6" fill="#ffd166"/></svg></span>';

    function norm(s) {
        return String(s == null ? '' : s).replace(/\s+/g, ' ').trim().toLowerCase();
    }
    function numOf(s) {
        var m = String(s).replace(/\./g, '').replace(',', '.').match(/-?\d+(\.\d+)?/);
        return m ? parseFloat(m[0]) : NaN;
    }
    function stiffRank(s) {
        s = norm(s);
        if (s.indexOf('rất cứng') >= 0) return 4;
        if (s.indexOf('cứng') >= 0) return 3;
        if (s.indexOf('trung bình') >= 0) return 2;
        if (s.indexOf('dẻo') >= 0) return 1;
        return 0;
    }
    function matRank(s, shaft) {
        s = norm(s); var r = 1;
        if (s.indexOf('graphite') >= 0 || s.indexOf('carbon') >= 0) r = 2;
        if (s.indexOf('hm graphite') >= 0 || s.indexOf('high modulus') >= 0) r = 3;
        if (s.indexOf('namd') >= 0) r = 4;
        if (s.indexOf('nano carbon') >= 0 || s.indexOf('ống nano') >= 0) r += 0.5;
        if (shaft && (s.indexOf('super slim') >= 0 || s.indexOf('hyper slim') >= 0)) r += 0.5;
        return r;
    }

    /* Mỗi tiêu chí: cách chấm điểm + nhãn giải thích + chiều tốt hơn */
    var RULES = [
        { key: 'giá từ',        score: function (t) { return -numOf(t); },            tip: 'Giá thấp hơn' },
        { key: 'căng tối đa',   score: function (t) { return numOf(t); },             tip: 'Căng tối đa cao hơn – khung chịu lực tốt hơn' },
        { key: 'độ cứng thân',  score: function (t) { return stiffRank(t); },         tip: 'Thân cứng hơn – truyền lực nhanh, hợp người chơi khá' },
        { key: 'vật liệu khung',score: function (t) { return matRank(t, false); },    tip: 'Vật liệu khung cao cấp hơn' },
        { key: 'vật liệu thân', score: function (t) { return matRank(t, true); },     tip: 'Vật liệu thân cao cấp hơn' }
    ];

    function crownTable(table) {
        if (table.dataset.crowned) return;
        table.dataset.crowned = '1';
        var rows = table.querySelectorAll('tbody tr');
        rows.forEach(function (tr) {
            var cells = tr.children;
            if (cells.length < 3) return;
            var label = norm(cells[0].textContent);
            var rule = null;
            for (var i = 0; i < RULES.length; i++) {
                if (label.indexOf(RULES[i].key) === 0) { rule = RULES[i]; break; }
            }
            if (!rule) return;
            var vals = [], best = -Infinity, allSame = true, first = null;
            for (var c = 1; c < cells.length; c++) {
                var t = cells[c].textContent;
                var s = rule.score(t);
                vals.push(s);
                if (isNaN(s)) { allSame = false; continue; }
                if (first === null) first = s; else if (s !== first) allSame = false;
                if (s > best) best = s;
            }
            if (allSame || best === -Infinity) return;
            for (var k = 1; k < cells.length; k++) {
                if (vals[k - 1] === best) {
                    cells[k].insertAdjacentHTML('beforeend', CROWN.replace('{T}', rule.tip));
                }
            }
        });
        /* Chú thích ngay trên bảng để người xem hiểu vương miện nghĩa là gì */
        if (!table.previousElementSibling || !table.previousElementSibling.classList.contains('cmp-legend')) {
            var legend = document.createElement('div');
            legend.className = 'cmp-legend';
            legend.innerHTML = CROWN.replace('{T}', 'Tốt hơn ở tiêu chí này') +
                '<span>= tốt hơn ở tiêu chí đó: giá thấp hơn, căng tối đa cao hơn, thân cứng hơn, vật liệu cao cấp hơn. ' +
                'Điểm cân bằng / kiểu cân bằng / chiều dài là <strong>phong cách chơi</strong>, không phải hơn kém.</span>';
            table.parentNode.insertBefore(legend, table);
        }
    }

    /* Gợi ý phí giao hàng -> nút mở/đóng, dùng Bootstrap collapse sẵn có */
    var SHIP_TEXT = 'Phí giao hàng tính theo địa chỉ GHN đã chọn';
    function shipToggle() {
        var nodes = document.querySelectorAll('p, .form-text, .small, div, span');
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.dataset.shipBound) continue;
            var txt = (el.textContent || '').replace(/\s+/g, ' ').trim();
            if (txt.indexOf(SHIP_TEXT) !== 0 || el.children.length > 2) continue;
            el.dataset.shipBound = '1';
            var id = 'shipHintBody';
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'ship-hint-toggle';
            btn.setAttribute('data-bs-toggle', 'collapse');
            btn.setAttribute('data-bs-target', '#' + id);
            btn.setAttribute('aria-expanded', 'false');
            btn.setAttribute('aria-controls', id);
            btn.innerHTML = '<i class="bi bi-truck" aria-hidden="true"></i> Phí giao hàng tính';
            var body = document.createElement('div');
            body.className = 'collapse';
            body.id = id;
            var inner = document.createElement('div');
            inner.className = 'ship-hint-body';
            inner.textContent = txt;
            body.appendChild(inner);
            el.textContent = '';
            el.appendChild(btn);
            el.appendChild(body);
            break;
        }
    }

    function init() {
        document.querySelectorAll('.compare-table').forEach(crownTable);
        shipToggle();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();