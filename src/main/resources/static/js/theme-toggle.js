// Nút sáng/tối: lưu localStorage, áp data-bs-theme + class theme-dark.
// Bảng chọn mở bằng CSS :hover / :focus-within (không có cờ đóng cưỡng bức nữa).
// Click chuột: nhả focus để focus không giữ bảng mở sau khi rời chuột,
// nhưng bảng VẪN mở nhờ :hover → bấm Dark rồi Light liên tục vẫn ăn.
(function () {
    var KEY = 'badstore-theme';
    var root = document.documentElement;

    function hienThi(t) {
        root.classList.toggle('theme-dark', t === 'dark');
        root.setAttribute('data-bs-theme', t);
        document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
            b.setAttribute('aria-pressed', String(b.dataset.themeChoice === t));
        });
        var ico = document.querySelector('.theme-switch-ico');
        if (ico) ico.className = 'bi theme-switch-ico ' + (t === 'dark' ? 'bi-moon-stars-fill' : 'bi-sun-fill');
    }

    function chon(t) {
        try { localStorage.setItem(KEY, t); } catch (e) {}
        hienThi(t);
    }

    document.addEventListener('click', function (e) {
        var b = e.target.closest ? e.target.closest('[data-theme-choice]') : null;
        if (!b) return;
        chon(b.dataset.themeChoice);
        if (e.detail > 0) b.blur(); // chuột thật: nhả focus, bảng vẫn mở nhờ :hover
    });

    // Bàn phím: Escape đóng bảng (nhả focus để :focus-within không giữ mở).
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') return;
        var a = document.activeElement;
        if (a && a.closest && a.closest('.theme-switch')) a.blur();
    });

    // Chưa lưu thì theo hệ điều hành; base.html đã áp sớm để tránh nháy sáng.
    var t = null;
    try { t = localStorage.getItem(KEY); } catch (e) {}
    if (t !== 'light' && t !== 'dark') {
        t = window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
        // Chưa chọn tay: theo dõi OS đổi chế độ thì đổi theo.
        if (window.matchMedia) {
            var mq = matchMedia('(prefers-color-scheme: dark)');
            if (mq.addEventListener) mq.addEventListener('change', function (ev) {
                var saved = null;
                try { saved = localStorage.getItem(KEY); } catch (err) {}
                if (saved !== 'light' && saved !== 'dark') hienThi(ev.matches ? 'dark' : 'light');
            });
        }
    }
    hienThi(t);
})();