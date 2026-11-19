/* =============================================================================
   BadStore – JS riêng TRANG CHI TIẾT SẢN PHẨM (chỉ nạp ở shop/product-detail.html)

   Trang chi tiết hiển thị 3 nhóm lựa chọn riêng: Màu sắc → Trọng lượng → Cỡ cán.
   Toàn bộ tổ hợp lấy từ các thẻ [data-pdp-variant] do server render từ bảng
   product_variants, nên JS KHÔNG tự ghép màu × trọng lượng × cỡ cán.

   Luồng chính:
   (1) Chọn màu ⇒ chỉ mở các trọng lượng có thật của màu đó; nếu trọng lượng đang
       chọn không còn tồn tại thì tự chuyển sang mức gần nhất còn hàng.
   (2) Chọn trọng lượng ⇒ chỉ mở các cỡ cán có thật của màu + trọng lượng đó.
   (3) Chọn cỡ cán ⇒ xác định đúng variantId và đồng bộ SKU, giá, giá gốc, ảnh,
       tồn kho, ô số lượng và trạng thái nút mua.
   (4) Biến thể hết hàng vẫn xem được nhưng nút mua bị vô hiệu hoá và có cảnh báo.
   (5) Nút "Chọn" ở bảng phiên bản (tab Thông số) chọn đúng tổ hợp rồi cuộn lên.

   Giá/tồn kho hiển thị chỉ để khách tham khảo; CartService luôn đọc lại từ CSDL
   và kiểm tra variantId thuộc đúng productId trước khi thêm vào giỏ.
   ============================================================================= */
(function () {
    'use strict';

    /** Giới hạn mỗi dòng giỏ hàng – khớp CartService.MAX_QUANTITY_PER_LINE. */
    var MAX_QUANTITY = 20;
    var SEPARATOR = '\u0000';

    /** Định dạng tiền ở trình duyệt cho khớp server: 1.250.000 ₫ */
    function formatMoney(value) {
        return new Intl.NumberFormat('vi-VN').format(Number(value || 0)) + ' ₫';
    }

    function key() {
        return Array.prototype.join.call(arguments, SEPARATOR);
    }

    /**
     * Khoá so sánh văn bản – khớp ColorNames.key() ở server:
     * trim, gộp khoảng trắng, NFC và lowercase. Nhờ vậy dữ liệu cũ "Xanh"/"xanh"
     * vẫn được gom chung một nhóm trước khi admin sửa lại.
     */
    function normalized(value) {
        var text = String(value == null ? '' : value).trim().replace(/\s+/g, ' ');
        if (typeof text.normalize === 'function') {
            text = text.normalize('NFC');
        }
        return text.toLowerCase();
    }

    function colorKey(color) {
        return normalized(color);
    }

    function toNumber(value) {
        var number = Number(value);
        return isNaN(number) ? 0 : number;
    }

    function initBuyBox() {
        var form = document.querySelector('[data-pdp-form]');
        if (!form) {
            return;
        }

        var variantNodes = Array.prototype.slice.call(form.querySelectorAll('[data-pdp-variant]'));
        if (variantNodes.length === 0) {
            return;
        }

        var colorInputs = Array.prototype.slice.call(form.querySelectorAll('[data-pdp-color]'));
        var weightInputs = Array.prototype.slice.call(form.querySelectorAll('[data-pdp-weight]'));
        var gripInputs = Array.prototype.slice.call(form.querySelectorAll('[data-pdp-grip]'));
        if (colorInputs.length === 0 || weightInputs.length === 0 || gripInputs.length === 0) {
            return;
        }

        var hiddenVariant = form.querySelector('[data-pdp-variant-id]');
        var quantity = form.querySelector('input[name="quantity"]');
        var priceBox = document.querySelector('[data-pdp-price]');
        var oldPriceBox = document.querySelector('[data-pdp-old-price]');
        var stockText = document.querySelector('[data-pdp-stock-text]');
        var stickyPriceBox = document.querySelector('[data-pdp-price-sticky]');
        var skuBox = document.querySelector('[data-pdp-sku]');
        var statusBox = document.querySelector('[data-pdp-selection-status]');
        var selectedColorBox = document.querySelector('[data-pdp-selected-color]');
        var selectedWeightBox = document.querySelector('[data-pdp-selected-weight]');
        var selectedGripBox = document.querySelector('[data-pdp-selected-grip]');
        var unavailableBox = document.querySelector('[data-pdp-unavailable]');
        var unavailableText = document.querySelector('[data-pdp-unavailable-text]');
        var addButtons = Array.prototype.slice.call(document.querySelectorAll('[data-pdp-add-to-cart]'));
        var productName = form.getAttribute('data-product-name') || '';

        // Input ẩn chỉ được đặt tên ngay tại đây: nếu JS không chạy, trình duyệt dùng
        // <select name="variantId"> dự phòng trong <noscript> thay vì gửi giá trị rỗng.
        hiddenVariant.setAttribute('name', 'variantId');

        // ===== 1. Đọc ma trận biến thể thật do server render =====
        var variants = variantNodes.map(function (node) {
            return {
                id: node.getAttribute('data-variant-id'),
                sku: node.getAttribute('data-sku') || '',
                color: node.getAttribute('data-color') || '',
                weight: node.getAttribute('data-weight') || '',
                grip: node.getAttribute('data-grip') || '',
                price: toNumber(node.getAttribute('data-price')),
                originalPrice: toNumber(node.getAttribute('data-original-price')),
                stock: parseInt(node.getAttribute('data-stock') || '0', 10),
                image: (node.getAttribute('data-image') || '').trim()
            };
        });

        var variantsById = {};
        var variantsByColor = {};
        var variantsByColorWeight = {};
        var variantsByCombination = {};

        variants.forEach(function (variant) {
            variantsById[String(variant.id)] = variant;
            pushToMap(variantsByColor, colorKey(variant.color), variant);
            pushToMap(variantsByColorWeight, key(colorKey(variant.color), variant.weight), variant);
            variantsByCombination[key(colorKey(variant.color), variant.weight, variant.grip)] = variant;
        });

        function pushToMap(map, mapKey, value) {
            if (!map[mapKey]) {
                map[mapKey] = [];
            }
            map[mapKey].push(value);
        }

        function variantsOfColor(color) {
            return variantsByColor[colorKey(color)] || [];
        }

        function variantsOfColorWeight(color, weight) {
            return variantsByColorWeight[key(colorKey(color), weight)] || [];
        }

        function exactVariant(color, weight, grip) {
            return variantsByCombination[key(colorKey(color), weight, grip)] || null;
        }

        function hasStock(list) {
            return list.some(function (variant) {
                return variant.stock > 0;
            });
        }

        // ===== 2. Tiện ích đọc/ghi nhóm lựa chọn =====
        function checkedInput(inputs) {
            for (var i = 0; i < inputs.length; i++) {
                if (inputs[i].checked) {
                    return inputs[i];
                }
            }
            return null;
        }

        function checkedValue(inputs) {
            var input = checkedInput(inputs);
            return input ? input.value : null;
        }

        function setChecked(inputs, value) {
            inputs.forEach(function (input) {
                input.checked = value != null && normalized(input.value) === normalized(value);
            });
        }

        function isDisabled(inputs, value) {
            for (var i = 0; i < inputs.length; i++) {
                if (normalized(inputs[i].value) === normalized(value)) {
                    return inputs[i].disabled;
                }
            }
            return true;
        }

        function labelOf(input) {
            if (input.labels && input.labels.length > 0) {
                return input.labels[0];
            }
            return document.querySelector('label[for="' + input.id + '"]');
        }

        /** Đánh dấu label: không có tổ hợp (is-unavailable) hoặc có tổ hợp nhưng hết hàng (is-out). */
        function setOptionState(input, unavailable, outOfStock) {
            input.disabled = unavailable;
            if (unavailable) {
                input.checked = false;
            }
            var label = labelOf(input);
            if (!label) {
                return;
            }
            label.classList.toggle('is-unavailable', unavailable);
            label.classList.toggle('is-out', !unavailable && outOfStock);
            if (unavailable) {
                label.title = 'Tổ hợp này không có trong cửa hàng';
            } else if (outOfStock) {
                label.title = 'Tổ hợp này tạm hết hàng';
            } else {
                label.removeAttribute('title');
            }
        }

        /**
         * Chọn giá trị dự phòng khi lựa chọn hiện tại không còn hợp lệ.
         * Ưu tiên tổ hợp còn hàng, sau đó mới tới tổ hợp có thật nhưng hết hàng.
         */
        function pickValue(inputs, listOfValue, preferStock) {
            var fallback = null;
            for (var i = 0; i < inputs.length; i++) {
                var input = inputs[i];
                if (input.disabled) {
                    continue;
                }
                var list = listOfValue(input.value);
                if (!list || list.length === 0) {
                    continue;
                }
                if (fallback === null) {
                    fallback = input.value;
                }
                if (preferStock && hasStock(list)) {
                    return input.value;
                }
            }
            return fallback;
        }

        // ===== 3. Ảnh lớn: ưu tiên ảnh biến thể, fallback ảnh cùng màu đã gắn ở server =====
        var gallery = document.getElementById('productGallery');
        var gallerySlides = gallery
            ? Array.prototype.slice.call(gallery.querySelectorAll('[data-pdp-slide]'))
            : [];
        var singleImage = gallery ? null : document.querySelector('[data-pdp-single]');
        var swappedImage = null;

        function sameImageUrl(left, right) {
            if (!left || !right) {
                return false;
            }
            function normalize(url) {
                try {
                    return new URL(url, window.location.origin).pathname.replace(/\/+$/, '').toLowerCase();
                } catch (error) {
                    return String(url).toLowerCase();
                }
            }
            return normalize(left) === normalize(right);
        }

        function restoreSwappedImage() {
            if (!swappedImage) {
                return;
            }
            var image = swappedImage.element;
            image.setAttribute('src', swappedImage.src);
            if (swappedImage.alt == null) {
                image.removeAttribute('alt');
            } else {
                image.setAttribute('alt', swappedImage.alt);
            }
            image.onerror = null;
            swappedImage = null;
        }

        function showVariantImage(wanted, wantedAlt) {
            wanted = (wanted || '').trim();
            if (!wanted) {
                restoreSwappedImage();
                return;
            }

            if (gallery && window.bootstrap && window.bootstrap.Carousel) {
                var matchIndex = -1;
                gallerySlides.forEach(function (slide, index) {
                    if (sameImageUrl(slide.getAttribute('src'), wanted)) {
                        matchIndex = index;
                    }
                });
                if (matchIndex >= 0) {
                    restoreSwappedImage();
                    window.bootstrap.Carousel.getOrCreateInstance(gallery).to(matchIndex);
                    return;
                }
            }

            var target = gallery
                ? gallery.querySelector('.carousel-item.active [data-pdp-slide]')
                : singleImage;
            if (!target) {
                restoreSwappedImage();
                return;
            }
            if (swappedImage && swappedImage.element !== target) {
                restoreSwappedImage();
            }
            if (!swappedImage) {
                swappedImage = {
                    element: target,
                    src: target.getAttribute('src'),
                    alt: target.getAttribute('alt')
                };
            }
            target.setAttribute('src', wanted);
            if (wantedAlt) {
                target.setAttribute('alt', wantedAlt);
            }
            // Ảnh biến thể lỗi thì trả lại ảnh gốc để khách không thấy ảnh vỡ
            target.onerror = function () {
                restoreSwappedImage();
            };
        }

        // ===== 4. Đồng bộ toàn bộ khối mua hàng theo biến thể đang chọn =====
        function setUnavailable(show, message) {
            if (!unavailableBox) {
                return;
            }
            unavailableBox.classList.toggle('d-none', !show);
            if (show && unavailableText) {
                unavailableText.textContent = message;
            }
        }

        function render(variant, color, weight, grip) {
            if (selectedColorBox && color) {
                selectedColorBox.textContent = color;
            }
            if (selectedWeightBox && weight) {
                selectedWeightBox.textContent = weight;
            }
            if (selectedGripBox && grip) {
                selectedGripBox.textContent = grip;
            }

            if (!variant) {
                if (hiddenVariant) {
                    hiddenVariant.value = '';
                }
                setUnavailable(true, 'Tổ hợp ' + [color, weight, grip].filter(Boolean).join(' · ')
                    + ' chưa có trong cửa hàng. Vui lòng chọn tổ hợp khác.');
                if (statusBox) {
                    statusBox.textContent = 'Tổ hợp đang chọn chưa có trong cửa hàng.';
                }
                addButtons.forEach(function (button) {
                    button.disabled = true;
                });
                if (quantity) {
                    quantity.disabled = true;
                }
                return;
            }

            if (hiddenVariant) {
                hiddenVariant.value = String(variant.id);
            }
            if (skuBox) {
                skuBox.textContent = variant.sku;
            }
            if (priceBox) {
                priceBox.textContent = variant.price > 0 ? formatMoney(variant.price) : 'Liên hệ';
            }
            if (stickyPriceBox) {
                stickyPriceBox.textContent = variant.price > 0 ? formatMoney(variant.price) : 'Liên hệ';
            }
            if (oldPriceBox) {
                if (variant.originalPrice > variant.price) {
                    oldPriceBox.textContent = formatMoney(variant.originalPrice);
                    oldPriceBox.classList.remove('d-none');
                } else {
                    oldPriceBox.textContent = '';
                    oldPriceBox.classList.add('d-none');
                }
            }

            if (stockText) {
                stockText.textContent = variant.stock <= 0
                    ? 'Tạm hết hàng'
                    : (variant.stock <= 3
                        ? 'Sắp hết hàng – chỉ còn ' + variant.stock + ' sản phẩm'
                        : 'Còn ' + variant.stock + ' sản phẩm');
                stockText.classList.toggle('is-low', variant.stock > 0 && variant.stock <= 3);
                stockText.classList.toggle('is-out', variant.stock <= 0);
            }

            var outOfStock = variant.stock <= 0;
            addButtons.forEach(function (button) {
                button.disabled = outOfStock;
            });

            if (quantity) {
                var max = outOfStock ? 1 : Math.min(variant.stock, MAX_QUANTITY);
                quantity.setAttribute('max', String(max));
                var value = parseInt(quantity.value, 10);
                if (isNaN(value) || value < 1) {
                    value = 1;
                }
                quantity.value = String(Math.min(value, max));
                quantity.disabled = outOfStock;
            }

            if (statusBox) {
                statusBox.textContent = 'Đang chọn: ' + variant.color + ' · ' + variant.weight + ' · '
                    + variant.grip + ' · SKU ' + variant.sku;
            }
            setUnavailable(outOfStock, 'Phiên bản ' + variant.color + ' · ' + variant.weight + ' · '
                + variant.grip + ' đã hết hàng. Vui lòng chọn phiên bản khác.');

            showVariantImage(variant.image, 'Ảnh ' + (productName ? productName + ' – ' : '')
                + 'phiên bản ' + variant.color + ' ' + variant.weight + ' ' + variant.grip);
        }

        /**
         * Tính lại tổ hợp hợp lệ sau mỗi lần đổi lựa chọn.
         * Không bao giờ tự tạo tổ hợp: mọi nhánh đều tra trong variantsByCombination.
         */
        function refresh() {
            var colorInput = checkedInput(colorInputs);
            if (!colorInput) {
                colorInput = colorInputs.filter(function (input) {
                    return !input.disabled;
                })[0] || null;
                if (colorInput) {
                    colorInput.checked = true;
                }
            }
            if (!colorInput) {
                render(null, null, null, null);
                return;
            }
            var color = colorInput.value;

            // Màu: luôn có biến thể thật, chỉ đánh dấu màu đã hết hàng.
            colorInputs.forEach(function (input) {
                var label = labelOf(input);
                if (!label) {
                    return;
                }
                label.classList.remove('is-unavailable');
                label.classList.toggle('is-out', !hasStock(variantsOfColor(input.value)));
                if (!hasStock(variantsOfColor(input.value))) {
                    label.title = 'Màu này tạm hết hàng';
                } else {
                    label.removeAttribute('title');
                }
            });

            // Trọng lượng: chỉ mở mức có thật cho màu đang chọn.
            weightInputs.forEach(function (input) {
                var list = variantsOfColorWeight(color, input.value);
                setOptionState(input, list.length === 0, list.length > 0 && !hasStock(list));
            });
            var weight = checkedValue(weightInputs);
            if (!weight || isDisabled(weightInputs, weight)) {
                weight = pickValue(weightInputs, function (value) {
                    return variantsOfColorWeight(color, value);
                }, true);
                setChecked(weightInputs, weight);
            }

            // Cỡ cán: chỉ mở tổ hợp màu + trọng lượng đang có.
            gripInputs.forEach(function (input) {
                var variant = weight ? exactVariant(color, weight, input.value) : null;
                setOptionState(input, !variant, variant != null && variant.stock <= 0);
            });
            var grip = checkedValue(gripInputs);
            if (!grip || isDisabled(gripInputs, grip)) {
                grip = pickValue(gripInputs, function (value) {
                    return weight ? variantsOfColorWeight(color, weight).filter(function (variant) {
                        return variant.grip === value;
                    }) : [];
                }, true);
                setChecked(gripInputs, grip);
            }

            var variant = (color && weight && grip) ? exactVariant(color, weight, grip) : null;
            render(variant, color, weight, grip);
        }

        // ===== 5. Gắn sự kiện =====
        colorInputs.concat(weightInputs, gripInputs).forEach(function (input) {
            input.addEventListener('change', refresh);
        });

        // Nút "Chọn" ở bảng phiên bản: chọn đúng màu → trọng lượng → cỡ cán rồi cuộn lên.
        Array.prototype.slice.call(document.querySelectorAll('[data-pdp-pick]')).forEach(function (button) {
            button.addEventListener('click', function () {
                var variant = variantsById[String(button.getAttribute('data-pdp-pick'))];
                if (!variant) {
                    return;
                }
                setChecked(colorInputs, variant.color);
                refresh();
                setChecked(weightInputs, variant.weight);
                refresh();
                setChecked(gripInputs, variant.grip);
                refresh();

                var buyBox = document.querySelector('[data-pdp-buy]');
                if (buyBox) {
                    var reduceMotion = window.matchMedia
                        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
                    buyBox.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
                }
            });
        });

        // Nạp trước ảnh biến thể để lúc đổi màu không bị chớp trắng.
        variants.forEach(function (variant) {
            if (variant.image) {
                var preload = new Image();
                preload.src = variant.image;
            }
        });

        // Khách tự bấm mũi tên/thumbnail thì trả lại bộ ảnh gốc của sản phẩm.
        if (gallery) {
            gallery.addEventListener('slide.bs.carousel', restoreSwappedImage);
        }

        // Trạng thái ban đầu do server render đã đúng; refresh() kiểm tra lại một lần nữa
        // để chắc chắn không giữ tổ hợp ảo nếu dữ liệu render lệch nhau.
        refresh();
    }

    /** Gửi đánh giá xong server redirect về /san-pham/{id}#danh-gia ⇒ mở đúng tab Đánh giá. */
    function openReviewsTabFromHash() {
        if (window.location.hash !== '#danh-gia') {
            return;
        }
        var tabButton = document.getElementById('danh-gia-tab');
        if (tabButton && window.bootstrap && window.bootstrap.Tab) {
            window.bootstrap.Tab.getOrCreateInstance(tabButton).show();
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        initBuyBox();
        openReviewsTabFromHash();
    });

    // Khách đã ở trong trang rồi bấm liên kết tới #danh-gia (hoặc Back/Forward) cũng mở đúng tab
    window.addEventListener('hashchange', openReviewsTabFromHash);
})();
