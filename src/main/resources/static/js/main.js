/* BadStore – JavaScript dùng chung
   Nhiệm vụ: (1) toast thông báo flash bằng SweetAlert2, (2) hộp thoại xác nhận TRƯỚC mọi thao tác ghi CSDL,
   (3) khởi tạo AOS (hiệu ứng cuộn) và Swiper (slider banner), (4) bộ chọn số lượng (+/−). */
(function () {
    'use strict';

    var DEFAULT_CONFIRM_TEXT = 'Bạn chắc chắn muốn thực hiện thao tác này?';
    var TOAST_DURATION = 4000;
    var toastQueue = [];
    var toastOpen = false;

    /** Vị trí toast: góc phải trên ở desktop, giữa trên ở điện thoại để không tràn màn hình. */
    function toastPosition() {
        return window.matchMedia && window.matchMedia('(max-width: 575.98px)').matches
            ? 'top'
            : 'top-end';
    }

    var currentToastKey = '';

    function toastKey(options) {
        return (options.icon || '') + '|' + (options.title || '') + '|' + (options.html || '');
    }

    /** Xếp hàng thông báo; bỏ qua bản trùng đang hiện hoặc đang chờ (chống spam từ MỌI nguồn). */
    function queueToast(options) {
        if (!window.Swal) {
            return;
        }
        var key = toastKey(options);
        if (key === currentToastKey) {
            return;
        }
        var queued = toastQueue.some(function (item) {
            return toastKey(item) === key;
        });
        if (queued) {
            return;
        }
        toastQueue.push(options);
        drainToastQueue();
    }

    function drainToastQueue() {
        if (toastOpen || toastQueue.length === 0 || !window.Swal) {
            return;
        }
        toastOpen = true;
        var options = toastQueue.shift();
        currentToastKey = toastKey(options);
        Swal.fire(options).then(function () {
            toastOpen = false;
            currentToastKey = '';
            drainToastQueue();
        });
    }

    /** Khung toast chung: nhỏ gọn, tạm dừng đếm giờ khi rê chuột, có nút × đóng ngay. */
    function toastOptions(icon) {
        return {
            toast: true,
            position: toastPosition(),
            showConfirmButton: false,
            // Nút × cho phép đóng sớm thay vì chờ hết thời gian tự ẩn.
            showCloseButton: true,
            closeButtonAriaLabel: 'Đóng thông báo',
            timer: TOAST_DURATION,
            timerProgressBar: true,
            customClass: {
                popup: 'bad-toast bad-toast--' + icon,
                title: 'bad-toast__title',
                htmlContainer: 'bad-toast__html',
                timerProgressBar: 'bad-toast__bar',
                closeButton: 'bad-toast__close'
            },
            didOpen: function (element) {
                element.addEventListener('mouseenter', Swal.stopTimer);
                element.addEventListener('mouseleave', Swal.resumeTimer);
            }
        };
    }

    /** Toast góc phải trên – dùng chung cho cả thành công và thất bại. */
    function showToast(icon, title) {
        var options = toastOptions(icon);
        options.icon = icon;
        options.title = title;
        queueToast(options);
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /** Chữ cái đầu của họ tên (tối đa 2 ký tự) để làm avatar cho toast chào mừng. */
    function nameInitials(name) {
        var parts = String(name || '').trim().split(/\s+/).filter(Boolean);
        if (parts.length === 0) {
            return 'B';
        }
        var first = Array.from(parts[0])[0] || '';
        var last = parts.length > 1 ? (Array.from(parts[parts.length - 1])[0] || '') : '';
        return (first + last).toLocaleUpperCase('vi');
    }

    /**
     * Toast chào mừng sau đăng nhập: avatar chữ cái + lời chào ngắn, thay cho câu
     * "Đăng nhập thành công. Xin chào …" dài và dễ xuống dòng trên điện thoại.
     * Dữ liệu lấy từ data-login-* do FlashMessageAuthenticationSuccessHandler gắn vào.
     */
    function showLoginSuccessToast(flash) {
        var name = flash.dataset.loginName || '';
        if (!name) {
            showToast('success', flash.dataset.success);
            return;
        }
        var isAdmin = flash.dataset.loginAdmin === 'true';
        var options = toastOptions('success');
        options.timer = 4200;
        options.html = '<div class="bad-toast-login">'
            + '<span class="bad-toast-login__avatar" aria-hidden="true">' + escapeHtml(nameInitials(name))
            + '<span class="bad-toast-login__check"><i class="bi bi-check-lg"></i></span></span>'
            + '<div class="bad-toast-login__body">'
            + '<p class="bad-toast-login__eyebrow">'
            + (isAdmin ? 'Đăng nhập quản trị' : 'Đăng nhập thành công')
            + '</p>'
            + '<p class="bad-toast-login__title">Xin chào, <strong>' + escapeHtml(name) + '</strong>!</p>'
            + '<p class="bad-toast-login__meta">'
            + (isAdmin ? 'Chúc bạn làm việc hiệu quả.' : 'Chúc bạn mua sắm vui vẻ.')
            + '</p>'
            + '</div>'
            + '</div>';
        options.customClass.popup += ' bad-toast--login';
        queueToast(options);
    }

    /**
     * Toast riêng cho "đã thêm vào giỏ": hiện tên + phiên bản + số lượng và lối tắt sang giỏ.
     * Dữ liệu lấy từ các data-cart-* do CartController gắn vào #flash-notifications.
     */
    function showCartAddedToast(flash) {
        var name = flash.dataset.cartName || '';
        var variant = flash.dataset.cartVariant || '';
        var quantity = parseInt(flash.dataset.cartQuantity || '0', 10);
        var cartUrl = flash.dataset.cartUrl || '/gio-hang';
        if (!name) {
            showToast('success', flash.dataset.success);
            return;
        }
        var meta = [];
        if (variant) {
            meta.push(escapeHtml(variant));
        }
        if (quantity > 0) {
            meta.push('Số lượng: ' + quantity);
        }
        var options = toastOptions('success');
        options.timer = 5200;
        options.html = '<div class="bad-toast-cart">'
            + '<span class="bad-toast-cart__icon" aria-hidden="true"><i class="bi bi-bag-check-fill"></i></span>'
            + '<div class="bad-toast-cart__body">'
            + '<p class="bad-toast-cart__eyebrow">Đã thêm vào giỏ</p>'
            + '<p class="bad-toast-cart__product">' + escapeHtml(name) + '</p>'
            + (meta.length ? '<p class="bad-toast-cart__meta">' + meta.join(' · ') + '</p>' : '')
            + '</div>'
            + '<a class="bad-toast-cart__link" href="' + escapeHtml(cartUrl) + '">Xem giỏ</a>'
            + '</div>';
        options.customClass.popup += ' bad-toast--cart';
        queueToast(options);
    }

    /** Đọc flash message do server gắn vào #flash-notifications rồi bật toast. */
    function initFlashToasts() {
        var flash = document.getElementById('flash-notifications');
        if (!flash) {
            return;
        }
        var hasSwal = !!window.Swal;
        if (flash.dataset.success) {
            if (hasSwal && flash.dataset.cartName) {
                showCartAddedToast(flash);
            } else if (hasSwal && flash.dataset.loginName) {
                showLoginSuccessToast(flash);
            } else {
                showToast('success', flash.dataset.success);
            }
        }
        if (flash.dataset.error) {
            showToast('error', flash.dataset.error);
        }
        if (flash.dataset.info) {
            showToast('info', flash.dataset.info);
        }
        // Toast đã thay thế alert trùng lặp (đánh dấu data-flash-toast-only).
        // Không có SweetAlert2 thì alert vẫn hiện để khách không mất thông tin.
        if (hasSwal) {
            document.querySelectorAll('[data-flash-toast-only="true"]').forEach(function (alertElement) {
                alertElement.remove();
            });
        }
    }

    // app.js gọi lại hàm này để mọi toast trong site dùng chung một kiểu.
    window.badToast = showToast;

    /** Lấy màu thật từ theme.css để hộp xác nhận không hard-code màu trong JS. */
    function cssVar(name, fallback) {
        var value = window.getComputedStyle(document.documentElement).getPropertyValue(name);
        return value && value.trim() ? value.trim() : fallback;
    }

    /** Hộp thoại xác nhận trước khi submit form / theo link có data-confirm. */
    function askConfirm(message) {
        return Swal.fire({
            icon: 'warning',
            title: 'Xác nhận thao tác',
            text: message || DEFAULT_CONFIRM_TEXT,
            showCancelButton: true,
            confirmButtonText: 'Đồng ý',
            cancelButtonText: 'Huỷ bỏ',
            reverseButtons: true,
            customClass: {
                popup: 'bad-confirm',
                confirmButton: 'bad-confirm__ok',
                cancelButton: 'bad-confirm__cancel'
            }
        });
    }

    /**
     * Sau khi khách đồng ý: khoá TẤT CẢ nút gửi + gắn vòng xoay.
     * form.submit() KHÔNG gửi giá trị nút bấm → nếu nút đã bấm có name,
     * giữ giá trị đó bằng input ẩn rồi mới disable (không mất dữ liệu, chống bấm đúp).
     * Lưu innerHTML gốc vào data-old để app.js khôi phục khi bấm Back (bfcache).
     */
    function setFormLoading(form, submitter) {
        if (submitter && submitter.name) {
            var proxy = document.createElement('input');
            proxy.type = 'hidden';
            proxy.name = submitter.name;
            proxy.value = submitter.value;
            proxy.setAttribute('data-loading-proxy', '');
            form.appendChild(proxy);
        }
        form.querySelectorAll('button[type="submit"]').forEach(function (button) {
            button.dataset.old = button.innerHTML;
            button.disabled = true;
            button.innerHTML = '<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>Đang xử lý...';
        });
    }

    function initConfirmations() {
        document.querySelectorAll('form[data-confirm]').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                // app.js đánh dấu '1', main.js đánh dấu 'true' ⇒ chỉ cần truthy là đủ.
                if (form.dataset.confirmed || !window.Swal) {
                    return;
                }
                event.preventDefault();
                var submitter = event.submitter || null;
                askConfirm(form.dataset.confirm).then(function (result) {
                    if (result.isConfirmed) {
                        form.dataset.confirmed = 'true';
                        setFormLoading(form, submitter);
                        form.submit();
                    }
                });
            });
        });

        document.querySelectorAll('a[data-confirm]').forEach(function (link) {
            link.addEventListener('click', function (event) {
                if (!window.Swal) {
                    return;
                }
                event.preventDefault();
                askConfirm(link.dataset.confirm).then(function (result) {
                    if (result.isConfirmed) {
                        window.location.href = link.href;
                    }
                });
            });
        });
    }

    /** Nút +/- cạnh ô số lượng (giỏ hàng, trang chi tiết sản phẩm) – luôn kẹp trong khoảng min/max của ô. */
    function initQuantitySteppers() {
        document.querySelectorAll('.js-quantity').forEach(function (group) {
            var input = group.querySelector('input[type="number"]');
            if (!input) {
                return;
            }
            function step(delta) {
                var min = parseInt(input.getAttribute('min') || '1', 10);
                var max = parseInt(input.getAttribute('max') || '999', 10);
                var value = parseInt(input.value, 10);
                if (isNaN(value)) {
                    value = min;
                }
                value = value + delta;
                if (value < min) {
                    value = min;
                }
                if (value > max) {
                    value = max;
                }
                input.value = String(value);
            }
            var down = group.querySelector('.js-quantity-down');
            var up = group.querySelector('.js-quantity-up');
            if (down) {
                down.addEventListener('click', function () {
                    step(-1);
                });
            }
            if (up) {
                up.addEventListener('click', function () {
                    step(1);
                });
            }
        });
    }

    function initSwiper() {
        if (!window.Swiper) {
            return;
        }
        // Người bật "giảm chuyển động": tắt tự chạy, chỉ đổi slide khi người dùng bấm
        var reduceMotion = window.matchMedia
            && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        document.querySelectorAll('.js-swiper').forEach(function (element) {
            var swiper = new Swiper(element, {
                loop: true,
                // Dừng tự chạy khi rê chuột / focus bàn phím vào slider
                autoplay: reduceMotion ? false : { delay: 4500, disableOnInteraction: false, pauseOnMouseEnter: true },
                keyboard: { enabled: true },
                navigation: {
                    prevEl: element.querySelector('.hero-nav-prev'),
                    nextEl: element.querySelector('.hero-nav-next')
                },
                a11y: {
                    enabled: true,
                    prevSlideMessage: 'Banner trước',
                    nextSlideMessage: 'Banner sau',
                    paginationBulletMessage: 'Tới banner {{index}}'
                },
                pagination: {
                    el: element.querySelector('.swiper-pagination'),
                    clickable: true
                }
            });
            initSwiperPause(element, swiper, reduceMotion);
        });
    }

    /**
     * Nút tạm dừng banner (WCAG 2.2.2). Banner tự đổi mỗi 4,5 giây và lặp vô hạn nên phải
     * có cách dừng bằng bàn phím/chạm, không chỉ dừng khi rê chuột.
     * – Người dùng bấm nút: dừng hẳn cho tới khi bấm lại.
     * – Chỉ Tab vào slider (chưa bấm nút): tạm dừng để người dùng đọc/kịp bấm, rời focus thì chạy tiếp
     *   (trừ khi họ đã chủ động tạm dừng).
     * – reduced-motion: không autoplay ⇒ ẩn nút luôn, tránh nút vô nghĩa.
     */
    function initSwiperPause(element, swiper, reduceMotion) {
        var toggle = element.querySelector('[data-hero-autoplay-toggle]');
        if (!toggle || reduceMotion || !swiper.autoplay) {
            return;
        }
        var pausedByUser = false;

        function render() {
            toggle.setAttribute('aria-pressed', pausedByUser ? 'true' : 'false');
            var icon = toggle.querySelector('i');
            if (icon) {
                icon.className = pausedByUser ? 'bi bi-play-fill' : 'bi bi-pause-fill';
            }
        }

        toggle.hidden = false;
        render();

        toggle.addEventListener('click', function () {
            pausedByUser = !pausedByUser;
            if (pausedByUser) {
                swiper.autoplay.stop();
            } else {
                swiper.autoplay.start();
            }
            render();
        });

        element.addEventListener('focusin', function () {
            if (!pausedByUser) {
                swiper.autoplay.stop();
            }
        });

        element.addEventListener('focusout', function (event) {
            var next = event.relatedTarget;
            if (!pausedByUser && !(next && element.contains(next))) {
                swiper.autoplay.start();
            }
        });
    }

    /**
     * Form tạo đơn thủ công (admin): thêm/xoá dòng hàng và ĐÁNH LẠI chỉ số lines[i]
     * để Spring MVC bind đúng vào List<ManualOrderLineForm>.
     */
    function initManualOrderLines() {
        var container = document.querySelector('[data-manual-lines]');
        if (!container) {
            return;
        }
        var template = document.getElementById('manual-line-template');

        function rows() {
            return container.querySelectorAll('.manual-line');
        }

        function reindex() {
            rows().forEach(function (row, index) {
                row.querySelectorAll('[data-field]').forEach(function (field) {
                    field.name = 'lines[' + index + '].' + field.dataset.field;
                });
            });
            var onlyOneRow = rows().length === 1;
            rows().forEach(function (row) {
                var remove = row.querySelector('.js-remove-line');
                if (remove) {
                    remove.disabled = onlyOneRow;
                }
            });
        }

        container.addEventListener('click', function (event) {
            if (event.target.closest('[data-manual-line-add]')) {
                if (template) {
                    var row = template.content.firstElementChild.cloneNode(true);
                    container.appendChild(row);
                    reindex();
                    var skuInput = row.querySelector('input[data-field="sku"]');
                    if (skuInput) {
                        skuInput.focus();
                    }
                }
                return;
            }

            var removeButton = event.target.closest('.js-remove-line');
            if (removeButton && rows().length > 1) {
                removeButton.closest('.manual-line').remove();
                reindex();
            }
        });

        reindex();
    }

    /* Tich/bỏ tich tất cả cho bảng cập nhật giá hàng loạt ở trang quản trị biến thể. */
    function initBulkPriceTable() {
        var checkAll = document.getElementById('bulkCheckAll');
        if (!checkAll) {
            return;
        }
        var boxes = Array.prototype.slice.call(document.querySelectorAll('input[name="variantIds"]'));
        checkAll.addEventListener('change', function () {
            boxes.forEach(function (box) {
                box.checked = checkAll.checked;
            });
        });
        boxes.forEach(function (box) {
            box.addEventListener('change', function () {
                checkAll.checked = boxes.every(function (item) {
                    return item.checked;
                });
            });
        });
    }

    /** Định dạng tiền ở trình duyệt cho khớp với server (#,### ₫). */
    function formatMoney(value) {
        return new Intl.NumberFormat('vi-VN').format(Number(value || 0)) + ' ₫';
    }

    /** Gọi API JSON nội bộ, lỗi mạng thì ném ra để nơi gọi tự xử lý (giao diện không được vỡ). */
    function fetchJson(url) {
        return fetch(url, { headers: { Accept: 'application/json' } }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response.json();
        });
    }

    /**
     * Địa chỉ: dropdown địa chỉ lấy trực tiếp từ API GHN.
     *  – Mô hình 3 cấp cũ: Tỉnh → Quận/Huyện → Phường/Xã.
     *  – Mô hình 2 cấp mới: Tỉnh → Phường/Xã (GHN khuyến nghị từ 01/07/2025).
     * Mỗi lần chọn, tên địa danh được điền vào ô ẩn đi kèm mã GHN; server vẫn kiểm tra lại
     * mã (tồn tại thật) và tên (khớp mã) trước khi lưu nên khách không sửa được để ăn phí ship rẻ.
     */
    function initGhnAddressPicker() {
        var picker = document.querySelector('[data-ghn-picker]');
        if (!picker) {
            return;
        }
        var provinceSelect = picker.querySelector('[data-ghn-province]');
        var districtSelect = picker.querySelector('[data-ghn-district]');
        var wardSelect = picker.querySelector('[data-ghn-ward]');
        var provinceName = picker.querySelector('[data-ghn-province-name]');
        var districtName = picker.querySelector('[data-ghn-district-name]');
        var wardName = picker.querySelector('[data-ghn-ward-name]');
        var feeLabel = picker.querySelector('[data-ghn-fee]');
        var wardHint = picker.querySelector('[data-ghn-ward-hint]');
        // Lựa chọn cũ do server render lại sau khi form báo lỗi (Quận/Xã nạp lại bằng AJAX nên phải khôi phục).
        var restoreDistrict = picker.getAttribute('data-ghn-district') || '';
        var restoreWard = picker.getAttribute('data-ghn-ward') || '';
        var newAddressModel = picker.getAttribute('data-ghn-model') === 'new';
        // Shop chưa khai báo shopId ⇒ vẫn chọn được địa chỉ GHN, chỉ không tạm tính được phí.
        var shippingEnabled = picker.getAttribute('data-ghn-shipping') !== 'false';

        function setOptions(select, items, placeholder) {
            if (!select) {
                return;
            }
            var markup = '<option value="">' + placeholder + '</option>';
            items.forEach(function (item) {
                var name = String(item.name == null ? '' : item.name).replace(/&/g, '&amp;').replace(/</g, '&lt;');
                var attribute = String(item.name == null ? '' : item.name).replace(/"/g, '&quot;');
                markup += '<option value="' + item.id + '" data-name="' + attribute + '">' + name + '</option>';
            });
            select.innerHTML = markup;
            select.disabled = items.length === 0;
        }

        function selectedName(select) {
            if (!select) {
                return '';
            }
            var option = select.options[select.selectedIndex];
            return option ? (option.getAttribute('data-name') || '') : '';
        }

        /** Ghi tên địa danh của ô đang chọn vào ô ẩn để gửi kèm mã GHN. */
        function fillName(input, select) {
            if (input) {
                input.value = selectedName(select);
            }
        }

        function clearName(input) {
            if (input) {
                input.value = '';
            }
        }

        function inputValue(id) {
            var input = document.getElementById(id);
            return input ? input.value : '';
        }

        function setFee(text) {
            if (feeLabel) {
                feeLabel.textContent = text;
            }
        }

        /** Khu vực GHN không trả phường/xã nào – báo để khách đổi khu vực khác. */
        function setWardHint(visible) {
            if (wardHint) {
                wardHint.classList.toggle('d-none', !visible);
            }
        }

        function refreshFee() {
            if (!shippingEnabled) {
                return;
            }
            var wardCode = wardSelect ? wardSelect.value : '';

            if (newAddressModel) {
                var provinceId = provinceSelect ? provinceSelect.value : '';
                if (!provinceId || !wardCode) {
                    setFee('--');
                    return;
                }
                fetchJson('/api/ghn/phi-ship?provinceId=' + encodeURIComponent(provinceId)
                    + '&wardCode=' + encodeURIComponent(wardCode)
                    + '&provinceName=' + encodeURIComponent(selectedName(provinceSelect))
                    + '&wardName=' + encodeURIComponent(selectedName(wardSelect))
                    + '&addressDetail=' + encodeURIComponent(inputValue('addressDetail'))
                    + '&receiverName=' + encodeURIComponent(inputValue('receiverName'))
                    + '&receiverPhone=' + encodeURIComponent(inputValue('receiverPhone'))
                    + '&quantity=1')
                    .then(function (data) {
                        setFee(data.fee == null ? 'sẽ báo khi xác nhận' : formatMoney(data.fee));
                    })
                    .catch(function () {
                        setFee('chưa tính được');
                    });
                return;
            }

            var districtId = districtSelect ? districtSelect.value : '';
            if (!districtId) {
                setFee('--');
                return;
            }
            fetchJson('/api/ghn/phi-ship?districtId=' + encodeURIComponent(districtId)
                + '&wardCode=' + encodeURIComponent(wardCode) + '&quantity=1')
                .then(function (data) {
                    setFee(data.fee == null ? 'sẽ báo khi xác nhận' : formatMoney(data.fee));
                })
                .catch(function () {
                    setFee('chưa tính được');
                });
        }

        function resetWard() {
            setOptions(wardSelect, [], '-- Chọn phường/xã --');
            setWardHint(false);
            clearName(wardName);
            setFee('--');
        }

        function resetDistrictAndWard() {
            if (districtSelect) {
                setOptions(districtSelect, [], '-- Chọn quận/huyện --');
                clearName(districtName);
            }
            resetWard();
        }

        /** Tìm option theo giá trị (dùng khi khôi phục lựa chọn sau khi form báo lỗi). */
        function optionByValue(select, value) {
            if (!select || value == null) {
                return null;
            }
            return Array.prototype.find.call(select.options, function (option) {
                return option.value === String(value);
            }) || null;
        }

        /** Nạp quận/huyện của tỉnh đã chọn; trả về Promise để nơi gọi nạp tiếp phường/xã. */
        function loadDistricts(provinceId) {
            if (!districtSelect || !provinceId || newAddressModel) {
                return Promise.resolve();
            }
            districtSelect.innerHTML = '<option value="">Đang tải…</option>';
            districtSelect.disabled = true;
            return fetchJson('/api/ghn/huyen?provinceId=' + encodeURIComponent(provinceId))
                .then(function (data) {
                    setOptions(districtSelect, data.items || [], '-- Chọn quận/huyện --');
                })
                .catch(function () {
                    setOptions(districtSelect, [], '-- Chọn quận/huyện --');
                });
        }

        /**
         * Nạp phường/xã. Mô hình 3 cấp truyền districtId; mô hình 2 cấp truyền thẳng provinceId.
         */
        function loadWards(scopeId) {
            if (!wardSelect || !scopeId) {
                return Promise.resolve();
            }
            wardSelect.innerHTML = '<option value="">Đang tải…</option>';
            wardSelect.disabled = true;
            var url = newAddressModel
                ? '/api/ghn/xa?provinceId=' + encodeURIComponent(scopeId)
                : '/api/ghn/xa?districtId=' + encodeURIComponent(scopeId);
            return fetchJson(url)
                .then(function (data) {
                    var items = data.items || [];
                    setOptions(wardSelect, items, '-- Chọn phường/xã --');
                    setWardHint(items.length === 0);
                })
                .catch(function () {
                    setOptions(wardSelect, [], '-- Chọn phường/xã --');
                    setWardHint(false);
                });
        }

        /** Form hiển thị lại sau khi báo lỗi: khôi phục đúng Tỉnh → (Huyện) → Xã khách đã chọn. */
        function restoreSelection() {
            if (!provinceSelect || !provinceSelect.value) {
                refreshFee();
                return;
            }

            if (newAddressModel) {
                loadWards(provinceSelect.value).then(function () {
                    if (optionByValue(wardSelect, restoreWard)) {
                        wardSelect.value = restoreWard;
                        fillName(wardName, wardSelect);
                    }
                    refreshFee();
                });
                return;
            }

            if (!restoreDistrict) {
                refreshFee();
                return;
            }
            loadDistricts(provinceSelect.value).then(function () {
                if (!optionByValue(districtSelect, restoreDistrict)) {
                    return;
                }
                districtSelect.value = restoreDistrict;
                fillName(districtName, districtSelect);
                return loadWards(restoreDistrict).then(function () {
                    if (optionByValue(wardSelect, restoreWard)) {
                        wardSelect.value = restoreWard;
                        fillName(wardName, wardSelect);
                    }
                    refreshFee();
                });
            });
        }

        if (provinceSelect) {
            provinceSelect.addEventListener('change', function () {
                fillName(provinceName, provinceSelect);
                if (newAddressModel) {
                    resetWard();
                    loadWards(provinceSelect.value);
                } else {
                    resetDistrictAndWard();
                    loadDistricts(provinceSelect.value);
                }
            });
        }

        if (districtSelect) {
            districtSelect.addEventListener('change', function () {
                fillName(districtName, districtSelect);
                resetWard();
                loadWards(districtSelect.value);
                refreshFee();
            });
        }

        if (wardSelect) {
            wardSelect.addEventListener('change', function () {
                fillName(wardName, wardSelect);
                refreshFee();
            });
        }

        restoreSelection();
    }

    /**
     * Trang thanh toán: khách đổi địa chỉ nhận hàng thì xem trước phí giao hàng GHN.
     * Chỉ là con số THAM KHẢO trên giao diện – CheckoutService vẫn tính lại phí thật khi lưu đơn.
     */
    function initGhnFeePreview() {
        var panel = document.querySelector('[data-ghn-fee-preview]');
        if (!panel) {
            return;
        }
        var subtotal = Number(panel.dataset.subtotal || 0);
        var discount = Number(panel.dataset.discount || 0);
        var defaultFee = Number(panel.dataset.defaultFee || 0);
        var freeShipping = panel.dataset.freeShipping === 'true';
        var weight = Number(panel.dataset.weight || 0);
        var feeLabel = panel.querySelector('[data-shipping-fee]');
        var totalLabel = panel.querySelector('[data-order-total]');
        var sourceBadge = panel.querySelector('[data-shipping-source]');
        var shippingEnabled = panel.getAttribute('data-ghn-shipping') !== 'false';

        function applyFee(fee, fromGhn) {
            if (freeShipping) {
                return;
            }
            if (feeLabel) {
                feeLabel.textContent = formatMoney(fee);
            }
            if (sourceBadge) {
                sourceBadge.classList.toggle('d-none', !fromGhn);
            }
            if (totalLabel) {
                totalLabel.textContent = formatMoney(subtotal - discount + fee);
            }
        }

        function refresh() {
            if (!shippingEnabled) {
                applyFee(defaultFee, false);
                return;
            }
            var checked = document.querySelector('input[name="addressId"]:checked');
            var districtId = checked ? (checked.getAttribute('data-district-id') || '') : '';
            var wardCode = checked ? (checked.getAttribute('data-ward-code') || '') : '';
            var isNewAddress = checked && checked.getAttribute('data-new-address') === 'true';
            var url;

            if (isNewAddress) {
                var provinceId = checked.getAttribute('data-province-id') || '';
                var provinceName = checked.getAttribute('data-province-name') || '';
                var wardName = checked.getAttribute('data-ward-name') || '';
                if (!provinceId || !wardCode) {
                    applyFee(defaultFee, false);
                    return;
                }
                url = '/api/ghn/phi-ship?provinceId=' + encodeURIComponent(provinceId)
                    + '&wardCode=' + encodeURIComponent(wardCode)
                    + '&provinceName=' + encodeURIComponent(provinceName)
                    + '&wardName=' + encodeURIComponent(wardName)
                    + '&addressDetail=' + encodeURIComponent(checked.getAttribute('data-address-detail') || '')
                    + '&receiverName=' + encodeURIComponent(checked.getAttribute('data-receiver-name') || '')
                    + '&receiverPhone=' + encodeURIComponent(checked.getAttribute('data-receiver-phone') || '')
                    + '&weight=' + weight;
            } else {
                if (!districtId) {
                    applyFee(defaultFee, false);
                    return;
                }
                url = '/api/ghn/phi-ship?districtId=' + encodeURIComponent(districtId)
                    + '&wardCode=' + encodeURIComponent(wardCode) + '&weight=' + weight;
            }

            fetchJson(url)
                .then(function (data) {
                    applyFee(data.fee == null ? defaultFee : Number(data.fee), data.fee != null);
                })
                .catch(function () {
                    applyFee(defaultFee, false);
                });
        }

        document.querySelectorAll('input[name="addressId"]').forEach(function (radio) {
            radio.addEventListener('change', refresh);
        });
        refresh();
    }

    /**
     * Nút hiện/ẩn mật khẩu ở trang đăng nhập/đăng ký (chỉ đổi type của ô nhập,
     * giá trị gửi lên server không thay đổi).
     */
    function initPasswordToggles() {
        document.querySelectorAll('[data-toggle-password]').forEach(function (button) {
            var input = document.querySelector(button.dataset.togglePassword);
            if (!input) {
                return;
            }
            button.addEventListener('click', function () {
                var isHidden = input.type === 'password';
                input.type = isHidden ? 'text' : 'password';
                button.setAttribute('aria-pressed', isHidden ? 'true' : 'false');
                button.setAttribute('aria-label', isHidden ? 'Ẩn mật khẩu' : 'Hiện mật khẩu');
                var icon = button.querySelector('i');
                if (icon) {
                    icon.className = isHidden ? 'bi bi-eye-slash' : 'bi bi-eye';
                }
                input.focus();
            });
        });
    }

    /**
     * Trang danh mục sản phẩm: form có data-auto-submit (bộ lọc hãng/loại và ô "Sắp xếp") tự gửi
     * ngay khi khách đổi lựa chọn. Vẫn là GET nên URL copy được, F5 không hỏi lại.
     * Riêng ô nhập khoảng giá KHÔNG tự gửi để tránh lọc liên tục khi khách đang gõ số.
     */
    function initAutoSubmitForms() {
        document.querySelectorAll('form[data-auto-submit]').forEach(function (form) {
            form.querySelectorAll('select, input[type="radio"], input[type="checkbox"]').forEach(function (control) {
                control.addEventListener('change', function () {
                    if (typeof form.requestSubmit === 'function') {
                        form.requestSubmit();
                    } else {
                        form.submit();
                    }
                });
            });
        });
    }

    /**
     * Header khách: đánh dấu mục menu của trang hiện tại bằng aria-current="page"
     * (hỗ trợ trình đọc màn hình). Suy ra từ URL nên không cần controller gắn biến mới.
     */
    function initNavCurrent() {
        var currentPath = window.location.pathname;
        document.querySelectorAll('.navbar-bad .navbar-nav a.nav-link[href]').forEach(function (link) {
            var href = link.getAttribute('href');
            if (!href || href.charAt(0) === '#') {
                return;
            }
            var url;
            try {
                url = new URL(link.href, window.location.origin);
            } catch (error) {
                return;
            }
            if (url.origin !== window.location.origin) {
                return;
            }
            var isCurrent = url.pathname === '/'
                ? currentPath === '/'
                : (currentPath === url.pathname || currentPath.indexOf(url.pathname + '/') === 0);
            if (isCurrent) {
                link.setAttribute('aria-current', 'page');
            }
        });
    }

    /** Nút in (data-print): thay cho onclick inline, dùng chung cho mọi trang có nút in. */
    function initPrintButtons() {
        document.querySelectorAll('[data-print]').forEach(function (button) {
            button.addEventListener('click', function () {
                window.print();
            });
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (window.AOS) {
            AOS.init({ duration: 600, once: true, offset: 40 });
        }
        initSwiper();
        initFlashToasts();
        initConfirmations();
        initQuantitySteppers();
        initPasswordToggles();
        initManualOrderLines();
        initBulkPriceTable();
        initGhnAddressPicker();
        initGhnFeePreview();
        initAutoSubmitForms();
        initNavCurrent();
        initPrintButtons();
    });
})();
