/*
 * BadStore – hành vi riêng trang giỏ hàng (nạp defer, sau main.js/app.js).
 * Tăng cường dần: không JS thì bảng hiện đủ, nút Cập nhật luôn thấy,
 * không checkbox / không thanh xoá nhóm / không lọc / không phân trang.
 * Chế độ "Chọn nhiều": chỉ khi bật mới hiện checkbox + thanh xoá nhóm;
 * bấm vào thân dòng để chọn (link và ô số lượng vẫn hoạt động bình thường).
 */
(function () {
  'use strict';
  var table = document.querySelector('.cart-table');
  if (!table || !table.tBodies[0]) return;
  var tbody = table.tBodies[0];
  var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr[data-cart-row]'));
  var emptyRow = tbody.querySelector('[data-cart-empty]');
  var PAGE_SIZE = 5;
  var state = { page: 1, sortKey: null, sortDir: 'desc', keyword: '', status: '' };
  var selectMode = false;

  var tools = document.querySelector('[data-cart-tools]');
  var keywordInput = document.querySelector('[data-cart-filter-keyword]');
  var statusSelect = document.querySelector('[data-cart-filter-status]');
  var selectToggle = document.querySelector('[data-cart-select-toggle]');
  var bulkBar = document.querySelector('[data-cart-bulk]');
  var bulkCount = document.querySelector('[data-cart-bulk-count]');
  var bulkSubmit = document.querySelector('[data-cart-bulk-submit]');
  var bulkIds = document.querySelector('[data-cart-bulk-ids]');
  var checkAll = document.querySelector('[data-cart-check-all]');
  var pager = document.querySelector('[data-cart-pager]');
  var pagerList = pager ? pager.querySelector('.pagination') : null;
  var pagerMeta = document.querySelector('[data-cart-pager-meta]');

  function rowCheck(row) { return row.querySelector('[data-cart-check]'); }
  function isVisible(row) { return !row.hidden; }

  function filtered() {
    return rows.filter(function (row) {
      if (state.keyword && (row.getAttribute('data-search') || '').indexOf(state.keyword) === -1) return false;
      if (state.status === 'ok' && row.getAttribute('data-available') !== '1') return false;
      if (state.status === 'issue' && row.getAttribute('data-available') === '1') return false;
      return true;
    });
  }

  function sorted(list) {
    if (!state.sortKey) return list;
    var attr = state.sortKey === 'price' ? 'data-price' : 'data-total';
    var dir = state.sortDir === 'asc' ? 1 : -1;
    return list.slice().sort(function (a, b) {
      var d = (Number(a.getAttribute(attr)) - Number(b.getAttribute(attr))) * dir;
      // Bằng nhau thì giữ thứ tự gốc để bảng không nhảy loạn.
      return d !== 0 ? d : rows.indexOf(a) - rows.indexOf(b);
    });
  }

  function renderPager(pages, total) {
    if (!pagerList) return;
    pagerList.innerHTML = '';
    if (pages <= 1) { pager.hidden = true; return; }
    pager.hidden = false;
    function addItem(label, page, disabled, current, extraClass) {
      var li = document.createElement('li');
      li.className = 'page-item' + (disabled ? ' disabled' : '') + (current ? ' active' : '') + (extraClass ? ' ' + extraClass : '');
      var el;
      if (disabled || current) {
        el = document.createElement('span');
        el.className = 'page-link';
        if (current) el.setAttribute('aria-current', 'page');
        if (disabled) el.setAttribute('aria-disabled', 'true');
      } else {
        el = document.createElement('button');
        el.type = 'button';
        el.className = 'page-link';
        el.addEventListener('click', function () { state.page = page; apply(); });
      }
      el.textContent = label;
      li.appendChild(el);
      pagerList.appendChild(li);
    }
    addItem('Trước', state.page - 1, state.page === 1, false, 'page-step');
    var from = Math.max(1, state.page - 2);
    var to = Math.min(pages, from + 4);
    from = Math.max(1, to - 4);
    if (from > 1) { addItem('1', 1, false, false, 'page-edge'); if (from > 2) addItem('…', 0, true, false, 'page-gap'); }
    for (var n = from; n <= to; n++) addItem(String(n), n, false, n === state.page);
    if (to < pages) { if (to < pages - 1) addItem('…', 0, true, false, 'page-gap'); addItem(String(pages), pages, false, false, 'page-edge'); }
    addItem('Sau', state.page + 1, state.page === pages, false, 'page-step');
    if (pagerMeta) pagerMeta.textContent = 'Trang ' + state.page + '/' + pages + ' · ' + total + ' dòng';
  }

  function syncBulk() {
    rows.forEach(function (row) {
      var c = rowCheck(row);
      row.classList.toggle('cart-row-selected', !!(c && c.checked));
    });
    if (!bulkBar) return;
    var selected = rows.map(rowCheck).filter(function (c) { return c && c.checked; });
    bulkCount.textContent = 'Đã chọn ' + selected.length;
    bulkSubmit.disabled = selected.length === 0;
    bulkIds.innerHTML = '';
    selected.forEach(function (c) {
      var h = document.createElement('input');
      h.type = 'hidden';
      h.name = 'cartItemIds';
      h.value = c.value;
      bulkIds.appendChild(h);
    });
    var visible = rows.filter(isVisible).map(rowCheck).filter(Boolean);
    var checkedVisible = visible.filter(function (c) { return c.checked; });
    if (checkAll) {
      checkAll.checked = visible.length > 0 && checkedVisible.length === visible.length;
      checkAll.indeterminate = checkedVisible.length > 0 && checkedVisible.length < visible.length;
    }
  }

  function setSelectMode(on) {
    selectMode = on;
    table.classList.toggle('cart-select-mode', on);
    if (bulkBar) bulkBar.hidden = !on;
    if (checkAll) checkAll.hidden = !on;
    if (selectToggle) {
      selectToggle.setAttribute('aria-pressed', String(on));
      selectToggle.classList.toggle('btn-glass--primary', on);
      selectToggle.innerHTML = on
        ? '<i class="bi bi-x-lg" aria-hidden="true"></i> Thoát chọn'
        : '<i class="bi bi-check2-square" aria-hidden="true"></i> Chọn nhiều';
    }
    if (!on) {
      rows.forEach(function (row) {
        var c = rowCheck(row);
        if (c) c.checked = false;
      });
    }
    syncBulk();
  }

  function apply() {
    var list = sorted(filtered());
    var pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
    if (state.page > pages) state.page = pages;
    if (state.page < 1) state.page = 1;
    var start = (state.page - 1) * PAGE_SIZE;
    rows.forEach(function (row) { row.hidden = true; });
    list.forEach(function (row, i) {
      tbody.appendChild(row);
      row.hidden = i < start || i >= start + PAGE_SIZE;
    });
    if (emptyRow) { tbody.appendChild(emptyRow); emptyRow.hidden = list.length !== 0; }
    renderPager(pages, list.length);
    syncBulk();
  }

  // 1) Nút Cập nhật chỉ hiện khi số lượng khác giá trị server render.
  rows.forEach(function (row) {
    var form = row.querySelector('form.cart-qty-form');
    var input = form ? form.querySelector('input[name="quantity"]') : null;
    var btn = form ? form.querySelector('[data-cart-update]') : null;
    if (!input || !btn) return;
    var initial = input.value;
    function sync() { form.classList.toggle('cart-qty-clean', input.value === initial); }
    input.addEventListener('input', sync);
    input.addEventListener('change', sync);
    // Nút +/- của main.js gán value trực tiếp (không phát event input) → bắt click rồi kiểm tra lại.
    form.addEventListener('click', function (e) {
      if (e.target.closest('.js-quantity-up, .js-quantity-down')) setTimeout(sync, 0);
    });
    sync();
  });

  // 2) Lọc + sắp xếp + phân trang.
  if (tools) tools.hidden = false;
  if (keywordInput) keywordInput.addEventListener('input', function () {
    state.keyword = keywordInput.value.trim().toLowerCase(); state.page = 1; apply();
  });
  if (statusSelect) statusSelect.addEventListener('change', function () {
    state.status = statusSelect.value; state.page = 1; apply();
  });
  Array.prototype.forEach.call(document.querySelectorAll('[data-cart-sort]'), function (btn) {
    btn.addEventListener('click', function () {
      var key = btn.getAttribute('data-cart-sort');
      if (state.sortKey === key) state.sortDir = state.sortDir === 'desc' ? 'asc' : 'desc';
      else { state.sortKey = key; state.sortDir = 'desc'; }
      state.page = 1;
      Array.prototype.forEach.call(document.querySelectorAll('[data-cart-sort]'), function (b) {
        var th = b.closest('th');
        var active = b.getAttribute('data-cart-sort') === state.sortKey;
        if (th) th.setAttribute('aria-sort', active ? (state.sortDir === 'desc' ? 'descending' : 'ascending') : 'none');
        var icon = b.querySelector('i');
        if (icon) icon.className = active ? (state.sortDir === 'desc' ? 'bi bi-arrow-down' : 'bi bi-arrow-up') : 'bi bi-arrow-down-up';
      });
      apply();
    });
  });

  // 3) Chế độ chọn nhiều + xoá nhóm.
  if (selectToggle) selectToggle.addEventListener('click', function () { setSelectMode(!selectMode); });
  document.addEventListener('keydown', function (e) {
    // Escape đang mở modal thì để Bootstrap đóng modal, không thoát chế độ chọn.
    if (e.key !== 'Escape' || !selectMode) return;
    if (document.querySelector('.modal.show')) return;
    setSelectMode(false);
  });
  if (checkAll) checkAll.addEventListener('change', function () {
    if (!selectMode) return;
    rows.filter(isVisible).forEach(function (row) {
      var c = rowCheck(row);
      if (c) c.checked = checkAll.checked;
    });
    syncBulk();
  });
  tbody.addEventListener('change', function (e) {
    if (e.target.matches('[data-cart-check]')) syncBulk();
  });
  // Bấm thân dòng để chọn/bỏ chọn; KHÔNG cướp click của link, nút, ô nhập, form.
  tbody.addEventListener('click', function (e) {
    if (!selectMode) return;
    if (e.target.closest('a, button, input, select, textarea, label, form')) return;
    var row = e.target.closest('tr[data-cart-row]');
    if (!row) return;
    var c = rowCheck(row);
    if (c) { c.checked = !c.checked; syncBulk(); }
  });

  setSelectMode(false);
  apply();
})();