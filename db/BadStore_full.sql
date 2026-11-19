/* ==========================================================================================
   BadStore – FILE CSDL DUY NHẤT (bản gộp toàn bộ script SQL của dự án)
   ------------------------------------------------------------------------------------------
   Chạy MỘT LẦN trên SQL Server là có đủ schema + dữ liệu mẫu + dữ liệu demo:
     PHẦN 1 – Tạo database BadminStore, 14 bảng, khoá ngoại, CHECK và INDEX
     PHẦN 2 – Dữ liệu nền: 6 hãng vợt và 3 lối chơi
     PHẦN 3 – 15 cây vợt đầu tiên (biến thể + ảnh SVG)
     PHẦN 4 – 5 cây vợt bổ sung, đủ 20 mẫu và 49 biến thể
     PHẦN 5 – Tài khoản ADMIN mặc định
     PHẦN 6 – Dữ liệu demo: 11 khách, 5 voucher, 42 đơn, thanh toán, đánh giá, giỏ hàng

   CÁCH CHẠY (từ thư mục gốc dự án):
     sqlcmd -S localhost,1433 -U test -P 123456 -C -I -f 65001 -d master -i docs\BadStore_full.sql

   LƯU Ý
   - Chỉ chạy trên CSDL TRỐNG. Nếu BadminStore đã tồn tại, sao lưu rồi DROP trước khi chạy:
       ALTER DATABASE BadminStore SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
       DROP DATABASE BadminStore;
   - Tài khoản ADMIN: admin@badstore.local / Admin@123, khớp app.admin.* trong application-local.yml.
     Nếu đổi mật khẩu trong cấu hình, xoá dòng admin trong bảng users rồi khởi động app để app tạo lại.
   - Khách demo dùng chung mật khẩu Demo@123.
   - Bảng product_reviews đã nằm trong PHẦN 1 nên không cần chạy BadStore_patch_kha.sql nữa.
   - File này thay thế 5 file cũ: BadStore (1).sql, BadStore_patch_kha.sql,
     seed_san_pham.sql, seed_san_pham_bo_sung.sql và seed_demo_thuc_te.sql.
   ========================================================================================== */

/* ==========================================================================================
   PHẦN 1 – SCHEMA: DATABASE + 14 BẢNG
   Thứ tự tạo bảng theo khoá ngoại: users -> user_addresses -> brands/categories -> products
   -> product_variants -> product_images -> cart_items -> vouchers -> orders -> order_items
   -> payments -> order_status_histories -> product_reviews.
   ========================================================================================== */
IF DB_ID('BadminStore') IS NULL
    CREATE DATABASE BadminStore COLLATE Vietnamese_100_CI_AS;
GO
USE BadminStore;
GO

/* ===================== 1. USERS ===================== */
CREATE TABLE users (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    email         VARCHAR(100)  NOT NULL UNIQUE,
    password_hash VARCHAR(255)  NOT NULL,             -- BCrypt
    full_name     NVARCHAR(100) NOT NULL,
    phone         VARCHAR(20)   NULL,
    avatar        VARCHAR(500)  NULL,
    role          VARCHAR(20)   NOT NULL CONSTRAINT DF_users_role DEFAULT 'CUSTOMER',
    status        VARCHAR(20)   NOT NULL CONSTRAINT DF_users_status DEFAULT 'ACTIVE',
    created_at    DATETIME2     NOT NULL CONSTRAINT DF_users_created DEFAULT SYSDATETIME(),
    updated_at    DATETIME2     NOT NULL CONSTRAINT DF_users_updated DEFAULT SYSDATETIME(),
    CONSTRAINT CK_users_role   CHECK (role IN ('ADMIN','CUSTOMER')),
    CONSTRAINT CK_users_status CHECK (status IN ('ACTIVE','INACTIVE','LOCKED'))
);
GO

/* Địa chỉ giao hàng - id/code lưu NGUYÊN giá trị GHN trả về (dùng tính phí ship) */
CREATE TABLE user_addresses (
    id             INT IDENTITY(1,1) PRIMARY KEY,
    user_id        INT           NOT NULL,
    receiver_name  NVARCHAR(100) NOT NULL,
    receiver_phone VARCHAR(20)   NOT NULL,
    province_id    INT           NULL,               -- GHN ProvinceID
    province_name  NVARCHAR(100) NULL,
    district_id    INT           NULL,               -- GHN DistrictID
    district_name  NVARCHAR(100) NULL,
    ward_code      VARCHAR(20)   NULL,               -- GHN WardCode
    ward_name      NVARCHAR(100) NULL,
    address_detail NVARCHAR(255) NOT NULL,           -- số nhà, tên đường
    is_default     BIT           NOT NULL CONSTRAINT DF_addr_default DEFAULT 0,
    is_deleted     BIT           NOT NULL CONSTRAINT DF_addr_deleted DEFAULT 0,
    created_at     DATETIME2     NOT NULL CONSTRAINT DF_addr_created DEFAULT SYSDATETIME(),
    updated_at     DATETIME2     NOT NULL CONSTRAINT DF_addr_updated DEFAULT SYSDATETIME(),
    CONSTRAINT FK_user_addresses_users FOREIGN KEY (user_id) REFERENCES users(id)
);
GO
CREATE INDEX IX_user_addresses_user ON user_addresses(user_id);
-- Mỗi user tối đa 1 địa chỉ mặc định (đổi mặc định: bỏ cờ cũ TRƯỚC, đặt cờ mới SAU, trong 1 transaction)
CREATE UNIQUE INDEX UX_user_addresses_default ON user_addresses(user_id)
    WHERE is_default = 1 AND is_deleted = 0;
GO

/* ===================== 2. CATALOG ===================== */

CREATE TABLE brands (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    name        NVARCHAR(100) NOT NULL,
    description NVARCHAR(500) NULL,
    logo        VARCHAR(500)  NULL,
    is_deleted  BIT           NOT NULL CONSTRAINT DF_brands_deleted DEFAULT 0,
    created_at  DATETIME2     NOT NULL CONSTRAINT DF_brands_created DEFAULT SYSDATETIME(),
    updated_at  DATETIME2     NOT NULL CONSTRAINT DF_brands_updated DEFAULT SYSDATETIME()
);
GO
CREATE UNIQUE INDEX UX_brands_name ON brands(name) WHERE is_deleted = 0;

GO

/* Category = LỐI CHƠI (Tấn công / Phòng thủ / Toàn diện).
   Thông số kỹ thuật thật nằm ở bảng products. */
CREATE TABLE categories (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    name        NVARCHAR(100) NOT NULL,
    description NVARCHAR(500) NULL,
    is_deleted  BIT           NOT NULL CONSTRAINT DF_cat_deleted DEFAULT 0,
    created_at  DATETIME2     NOT NULL CONSTRAINT DF_cat_created DEFAULT SYSDATETIME(),
    updated_at  DATETIME2     NOT NULL CONSTRAINT DF_cat_updated DEFAULT SYSDATETIME()
);
GO
CREATE UNIQUE INDEX UX_categories_name ON categories(name) WHERE is_deleted = 0;
GO

/* Thông số chung của một mẫu vợt. Các cột dạng số/enum này cũng là dữ liệu để
   làm chức năng "so sánh sản phẩm" (mức Khá) mà không cần thêm bảng. */
CREATE TABLE products (
    id                INT IDENTITY(1,1) PRIMARY KEY,
    product_code      VARCHAR(30)   NOT NULL,
    name              NVARCHAR(200) NOT NULL,
    description       NVARCHAR(MAX) NULL,
    brand_id          INT           NOT NULL,
    category_id       INT           NOT NULL,

    length_mm         INT           NULL,            -- Chiều dài vợt
    balance_point_mm  INT           NULL,            -- Điểm cân bằng
    balance_type      VARCHAR(20)   NULL,            -- HEAD_HEAVY / EVEN / HEAD_LIGHT
    shaft_flexibility VARCHAR(20)   NULL,            -- SOFT / MEDIUM / STIFF / EXTRA_STIFF
    max_tension_kg    DECIMAL(4,1)  NULL,            -- Mức căng dây tối đa
    frame_material    NVARCHAR(150) NULL,
    shaft_material    NVARCHAR(150) NULL,

    status            VARCHAR(20)   NOT NULL CONSTRAINT DF_products_status DEFAULT 'ACTIVE',
    is_deleted        BIT           NOT NULL CONSTRAINT DF_products_deleted DEFAULT 0,
    created_at        DATETIME2     NOT NULL CONSTRAINT DF_products_created DEFAULT SYSDATETIME(),
    updated_at        DATETIME2     NOT NULL CONSTRAINT DF_products_updated DEFAULT SYSDATETIME(),

    CONSTRAINT FK_products_brand    FOREIGN KEY (brand_id)    REFERENCES brands(id),
    CONSTRAINT FK_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT CK_products_length   CHECK (length_mm IS NULL OR length_mm > 0),
    CONSTRAINT CK_products_balance  CHECK (balance_point_mm IS NULL OR balance_point_mm > 0),
    CONSTRAINT CK_products_tension  CHECK (max_tension_kg IS NULL OR max_tension_kg > 0),
    CONSTRAINT CK_products_balance_type CHECK (balance_type IS NULL
        OR balance_type IN ('HEAD_HEAVY','EVEN','HEAD_LIGHT')),
    CONSTRAINT CK_products_flex CHECK (shaft_flexibility IS NULL
        OR shaft_flexibility IN ('SOFT','MEDIUM','STIFF','EXTRA_STIFF')),
    CONSTRAINT CK_products_status CHECK (status IN ('ACTIVE','DRAFT','HIDDEN'))
);
GO
CREATE UNIQUE INDEX UX_products_code ON products(product_code) WHERE is_deleted = 0;
CREATE INDEX IX_products_brand    ON products(brand_id);
CREATE INDEX IX_products_category ON products(category_id);
GO

/* Biến thể = màu x trọng lượng (2U-5U) x cỡ cán (G3-G6).
   Mỗi biến thể có thể có ảnh riêng (image_url) -> dùng cho giỏ hàng + snapshot đơn hàng. */
CREATE TABLE product_variants (
    id             INT IDENTITY(1,1) PRIMARY KEY,
    product_id     INT           NOT NULL,
    sku            VARCHAR(50)   NOT NULL UNIQUE,
    color          NVARCHAR(50)  NOT NULL,
    weight_class   VARCHAR(10)   NOT NULL,           -- 2U, 3U, 4U, 5U (KHÔNG phải gram)
    grip_size      VARCHAR(10)   NOT NULL,           -- G3, G4, G5, G6
    image_url      VARCHAR(500)  NULL,
    original_price DECIMAL(18,0) NULL,               -- Giá gốc (nếu giảm giá)
    price          DECIMAL(18,0) NOT NULL,           -- giá bán hiện tại
    stock_quantity INT           NOT NULL CONSTRAINT DF_variants_stock DEFAULT 0,
    status         VARCHAR(20)   NOT NULL CONSTRAINT DF_variants_status DEFAULT 'ACTIVE',
    created_at     DATETIME2     NOT NULL CONSTRAINT DF_variants_created DEFAULT SYSDATETIME(),
    updated_at     DATETIME2     NOT NULL CONSTRAINT DF_variants_updated DEFAULT SYSDATETIME(),

    CONSTRAINT FK_product_variants_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT UQ_product_variant    UNIQUE (product_id, color, weight_class, grip_size),
    CONSTRAINT CK_variants_weight    CHECK (weight_class IN ('2U','3U','4U','5U')),
    CONSTRAINT CK_variants_grip      CHECK (grip_size IN ('G3','G4','G5','G6')),
    CONSTRAINT CK_variants_price     CHECK (price >= 0),
    CONSTRAINT CK_variants_original  CHECK (original_price IS NULL OR original_price >= price),
    CONSTRAINT CK_variants_stock     CHECK (stock_quantity >= 0),
    CONSTRAINT CK_variants_status    CHECK (status IN ('ACTIVE','INACTIVE'))
);
GO
-- Trang danh sách: lấy "giá từ" = MIN(price) theo sản phẩm, lọc theo khoảng giá
CREATE INDEX IX_variants_product_price ON product_variants(product_id, price) WHERE status = 'ACTIVE';
GO

/* Bộ ảnh (gallery) của sản phẩm */
CREATE TABLE product_images (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    product_id INT          NOT NULL,
    image_url  VARCHAR(500) NOT NULL,
    is_primary BIT          NOT NULL CONSTRAINT DF_images_primary DEFAULT 0,
    sort_order INT          NOT NULL CONSTRAINT DF_images_sort DEFAULT 0,
    created_at DATETIME2    NOT NULL CONSTRAINT DF_images_created DEFAULT SYSDATETIME(),
    CONSTRAINT FK_product_images_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT CK_product_images_sort CHECK (sort_order >= 0)
);
GO
CREATE INDEX IX_product_images_product ON product_images(product_id);
CREATE UNIQUE INDEX UX_product_images_primary ON product_images(product_id) WHERE is_primary = 1;
GO

/* ===================== 3. CART (gộp carts vào cart_items) ===================== */
CREATE TABLE cart_items (
    id                 INT IDENTITY(1,1) PRIMARY KEY,
    user_id            INT       NOT NULL,
    product_variant_id INT       NOT NULL,
    quantity           INT       NOT NULL CONSTRAINT DF_cart_items_qty DEFAULT 1,
    created_at         DATETIME2 NOT NULL CONSTRAINT DF_cart_items_created DEFAULT SYSDATETIME(),
    updated_at         DATETIME2 NOT NULL CONSTRAINT DF_cart_items_updated DEFAULT SYSDATETIME(),
    CONSTRAINT FK_cart_items_users    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT FK_cart_items_variants FOREIGN KEY (product_variant_id) REFERENCES product_variants(id),
    CONSTRAINT UQ_cart_items_user_variant UNIQUE (user_id, product_variant_id),
    CONSTRAINT CK_cart_items_quantity CHECK (quantity > 0)
);
GO
CREATE INDEX IX_cart_items_variant ON cart_items(product_variant_id);
GO

/* ===================== 4. VOUCHER (áp dụng cho tổng hóa đơn) ===================== */
CREATE TABLE vouchers (
    id                  INT IDENTITY(1,1) PRIMARY KEY,
    code                VARCHAR(50)   NOT NULL UNIQUE,
    name                NVARCHAR(100) NOT NULL,
    description         NVARCHAR(500) NULL,
    discount_type       VARCHAR(20)   NOT NULL,
    discount_value      DECIMAL(18,2) NOT NULL,      -- PERCENT: 0-100, FIXED: số tiền VND
    min_order_value     DECIMAL(18,0) NOT NULL CONSTRAINT DF_vouchers_min DEFAULT 0,
    max_discount_amount DECIMAL(18,0) NULL,
    quantity            INT           NOT NULL,      -- tổng lượt phát hành
    used_quantity       INT           NOT NULL CONSTRAINT DF_vouchers_used DEFAULT 0,
    start_at            DATETIME2     NOT NULL,
    end_at              DATETIME2     NOT NULL,
    status              VARCHAR(20)   NOT NULL CONSTRAINT DF_vouchers_status DEFAULT 'ACTIVE',
    created_at          DATETIME2     NOT NULL CONSTRAINT DF_vouchers_created DEFAULT SYSDATETIME(),
    updated_at          DATETIME2     NOT NULL CONSTRAINT DF_vouchers_updated DEFAULT SYSDATETIME(),

    CONSTRAINT CK_vouchers_type     CHECK (discount_type IN ('PERCENT','FIXED')),
    CONSTRAINT CK_vouchers_value    CHECK (discount_value >= 0),
    CONSTRAINT CK_vouchers_percent  CHECK (discount_type <> 'PERCENT' OR discount_value <= 100),
    CONSTRAINT CK_vouchers_min      CHECK (min_order_value >= 0),
    CONSTRAINT CK_vouchers_max      CHECK (max_discount_amount IS NULL OR max_discount_amount >= 0),
    CONSTRAINT CK_vouchers_qty      CHECK (quantity >= 0),
    CONSTRAINT CK_vouchers_used     CHECK (used_quantity >= 0 AND used_quantity <= quantity),
    CONSTRAINT CK_vouchers_date     CHECK (end_at > start_at),
    CONSTRAINT CK_vouchers_status   CHECK (status IN ('ACTIVE','INACTIVE'))
);
GO

/* ===================== 5. ORDER / PAYMENT ===================== */
CREATE TABLE orders (
    id                   INT IDENTITY(1,1) PRIMARY KEY,
    order_code           VARCHAR(30)   NOT NULL UNIQUE,

    -- ONLINE: khách tự đặt trên web (bắt buộc có user_id)
    -- MANUAL: admin tạo đơn thủ công (khách vãng lai/đặt qua điện thoại; user_id có thể NULL)
    order_source         VARCHAR(20)   NOT NULL CONSTRAINT DF_orders_source DEFAULT 'ONLINE',
    user_id              INT           NULL,
    created_by           INT           NULL,         -- admin tạo đơn thủ công
    address_id           INT           NULL,

    -- Snapshot thông tin khách/giao hàng tại thời điểm đặt
    receiver_name        NVARCHAR(100) NOT NULL,
    receiver_phone       VARCHAR(20)   NOT NULL,
    shipping_address     NVARCHAR(500) NULL,         -- NULL được phép với đơn MANUAL mua tại quầy
    shipping_province_id INT           NULL,         -- GHN
    shipping_district_id INT           NULL,         -- GHN
    shipping_ward_code   VARCHAR(20)   NULL,         -- GHN

    subtotal             DECIMAL(18,0) NOT NULL,
    shipping_fee         DECIMAL(18,0) NOT NULL CONSTRAINT DF_orders_ship DEFAULT 0,
    discount_amount      DECIMAL(18,0) NOT NULL CONSTRAINT DF_orders_discount DEFAULT 0,
    total_amount         DECIMAL(18,0) NOT NULL,
    voucher_id           INT           NULL,

    order_status         VARCHAR(30)   NOT NULL CONSTRAINT DF_orders_status DEFAULT 'PENDING',
    -- 1 = kho đã bị trừ cho đơn này (chỉ khi CONFIRMED/SHIPPING/DELIVERED). Xem CK_orders_stock
    stock_deducted       BIT           NOT NULL CONSTRAINT DF_orders_stock DEFAULT 0,
    note                 NVARCHAR(500) NULL,
    cancel_reason        NVARCHAR(500) NULL,         -- lý do hủy / hoàn trả
    ghn_order_code       VARCHAR(30)   NULL,         -- mã vận đơn GHN (nếu tạo đơn GHN)

    created_at           DATETIME2     NOT NULL CONSTRAINT DF_orders_created DEFAULT SYSDATETIME(),
    updated_at           DATETIME2     NOT NULL CONSTRAINT DF_orders_updated DEFAULT SYSDATETIME(),

    CONSTRAINT FK_orders_users      FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT FK_orders_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT FK_orders_addresses  FOREIGN KEY (address_id) REFERENCES user_addresses(id),
    CONSTRAINT FK_orders_vouchers   FOREIGN KEY (voucher_id) REFERENCES vouchers(id),
    CONSTRAINT CK_orders_source    CHECK (order_source IN ('ONLINE','MANUAL')),
    -- Đơn ONLINE phải có tài khoản + địa chỉ giao; đơn MANUAL phải biết admin nào tạo
    CONSTRAINT CK_orders_online    CHECK (order_source <> 'ONLINE'
        OR (user_id IS NOT NULL AND shipping_address IS NOT NULL)),
    CONSTRAINT CK_orders_manual    CHECK (order_source <> 'MANUAL' OR created_by IS NOT NULL),
    CONSTRAINT CK_orders_subtotal  CHECK (subtotal >= 0),
    CONSTRAINT CK_orders_shipping  CHECK (shipping_fee >= 0),
    CONSTRAINT CK_orders_discount  CHECK (discount_amount >= 0 AND discount_amount <= subtotal),
    CONSTRAINT CK_orders_total     CHECK (total_amount = subtotal + shipping_fee - discount_amount),
    CONSTRAINT CK_orders_status    CHECK (order_status IN
        ('PENDING','CONFIRMED','SHIPPING','DELIVERED','CANCELLED','RETURNED')),
    -- Chốt tính đúng đắn số lượng ở tầng DB: kho bị trừ <=> đơn đang ở CONFIRMED/SHIPPING/DELIVERED
    CONSTRAINT CK_orders_stock     CHECK (
        (order_status IN ('CONFIRMED','SHIPPING','DELIVERED') AND stock_deducted = 1)
     OR (order_status IN ('PENDING','CANCELLED','RETURNED')   AND stock_deducted = 0))
);
GO
CREATE INDEX IX_orders_user_created ON orders(user_id, created_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX IX_orders_status       ON orders(order_status, created_at DESC);
CREATE INDEX IX_orders_voucher      ON orders(voucher_id, user_id) WHERE voucher_id IS NOT NULL;
CREATE UNIQUE INDEX UX_orders_ghn_code ON orders(ghn_order_code) WHERE ghn_order_code IS NOT NULL;
GO

CREATE TABLE order_items (
    id                 INT IDENTITY(1,1) PRIMARY KEY,
    order_id           INT           NOT NULL,
    product_variant_id INT           NOT NULL,

    -- Snapshot sản phẩm tại thời điểm mua
    product_name       NVARCHAR(200) NOT NULL,
    sku                VARCHAR(50)   NOT NULL,
    color              NVARCHAR(50)  NOT NULL,
    weight_class       VARCHAR(10)   NOT NULL,
    grip_size          VARCHAR(10)   NOT NULL,
    image_url          VARCHAR(500)  NULL,

    unit_price         DECIMAL(18,0) NOT NULL,
    quantity           INT           NOT NULL,
    subtotal           DECIMAL(18,0) NOT NULL,

    CONSTRAINT FK_order_items_orders   FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT FK_order_items_variants FOREIGN KEY (product_variant_id) REFERENCES product_variants(id),
    CONSTRAINT UQ_order_items_order_variant UNIQUE (order_id, product_variant_id),
    CONSTRAINT CK_order_items_price    CHECK (unit_price >= 0),
    CONSTRAINT CK_order_items_qty      CHECK (quantity > 0),
    CONSTRAINT CK_order_items_subtotal CHECK (subtotal = unit_price * quantity)
);
GO
CREATE INDEX IX_order_items_variant ON order_items(product_variant_id);
GO

/* Thanh toán: COD, chuyển khoản (admin tự xác nhận đã nhận tiền), tiền mặt (đơn thủ công tại quầy) */
CREATE TABLE payments (
    id               INT IDENTITY(1,1) PRIMARY KEY,
    order_id         INT           NOT NULL UNIQUE,
    payment_method   VARCHAR(30)   NOT NULL,         -- COD | BANK_TRANSFER | CASH
    payment_status   VARCHAR(30)   NOT NULL CONSTRAINT DF_payments_status DEFAULT 'PENDING',
    amount           DECIMAL(18,0) NOT NULL,         -- = orders.total_amount (kiểm tra ở tầng ứng dụng)
    transaction_code VARCHAR(100)  NULL,             -- mã giao dịch ngân hàng (nếu có)
    confirmed_by     INT           NULL,             -- admin xác nhận đã nhận tiền
    paid_at          DATETIME2     NULL,
    created_at       DATETIME2     NOT NULL CONSTRAINT DF_payments_created DEFAULT SYSDATETIME(),
    updated_at       DATETIME2     NOT NULL CONSTRAINT DF_payments_updated DEFAULT SYSDATETIME(),

    CONSTRAINT FK_payments_orders       FOREIGN KEY (order_id)     REFERENCES orders(id),
    CONSTRAINT FK_payments_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users(id),
    CONSTRAINT CK_payments_method CHECK (payment_method IN ('COD','BANK_TRANSFER','CASH')),
    CONSTRAINT CK_payments_status CHECK (payment_status IN ('PENDING','PAID','FAILED','REFUNDED')),
    CONSTRAINT CK_payments_amount CHECK (amount >= 0),
    CONSTRAINT CK_payments_paid   CHECK (payment_status <> 'PAID' OR paid_at IS NOT NULL)
);
GO
CREATE UNIQUE INDEX UX_payments_transaction_code ON payments(transaction_code)
    WHERE transaction_code IS NOT NULL;
GO

/* Lịch sử thay đổi trạng thái - timeline đơn hàng + bằng chứng "ai đổi, khi nào" */
CREATE TABLE order_status_histories (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    order_id    INT           NOT NULL,
    from_status VARCHAR(30)   NULL,
    to_status   VARCHAR(30)   NOT NULL,
    changed_by  INT           NULL,                  -- NULL = hệ thống
    note        NVARCHAR(500) NULL,
    created_at  DATETIME2     NOT NULL CONSTRAINT DF_osh_created DEFAULT SYSDATETIME(),
    CONSTRAINT FK_osh_orders FOREIGN KEY (order_id)   REFERENCES orders(id),
    CONSTRAINT FK_osh_users  FOREIGN KEY (changed_by) REFERENCES users(id)
);
GO
CREATE INDEX IX_osh_order ON order_status_histories(order_id, created_at);
GO
CREATE TABLE product_reviews (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    product_id    INT           NOT NULL,
    user_id       INT           NOT NULL,
    order_item_id INT           NOT NULL,
    rating        TINYINT       NOT NULL,
    comment       NVARCHAR(1000) NULL,
    status        VARCHAR(20)   NOT NULL CONSTRAINT DF_reviews_status DEFAULT 'VISIBLE',
    created_at    DATETIME2     NOT NULL CONSTRAINT DF_reviews_created DEFAULT SYSDATETIME(),
    updated_at    DATETIME2     NOT NULL CONSTRAINT DF_reviews_updated DEFAULT SYSDATETIME(),
    CONSTRAINT FK_reviews_products    FOREIGN KEY (product_id)    REFERENCES products(id),
    CONSTRAINT FK_reviews_users       FOREIGN KEY (user_id)       REFERENCES users(id),
    CONSTRAINT FK_reviews_order_items FOREIGN KEY (order_item_id) REFERENCES order_items(id),
    CONSTRAINT UQ_reviews_order_item  UNIQUE (order_item_id),
    CONSTRAINT CK_reviews_rating      CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT CK_reviews_status      CHECK (status IN ('VISIBLE','HIDDEN'))
);
GO
CREATE INDEX IX_reviews_product ON product_reviews(product_id, status, created_at DESC);
GO

/* ==========================================================================================
   PHẦN 2 – DỮ LIỆU NỀN
   6 hãng vợt và 3 lối chơi (tấn công / phòng thủ / toàn diện) dùng làm khoá tra cứu cho
   các phần nạp sản phẩm bên dưới.
   ========================================================================================== */
INSERT INTO brands(name) VALUES
 (N'Yonex'), (N'Li-Ning'), (N'Victor'),
 (N'Mizuno'), (N'Kumpoo'), (N'Apacs');

INSERT INTO categories(name, description) VALUES
 (N'Vợt tấn công',    N'Dành cho lối chơi tấn công, smash mạnh'),
 (N'Vợt phòng thủ',  N'Dành cho lối chơi phòng thủ, phản tạt linh hoạt'),
 (N'Vợt toàn diện',  N'Dành cho lối chơi cân bằng công thủ');
-- Tài khoản ADMIN được nạp ở PHẦN 5 (hash BCrypt tương ứng mật khẩu Admin@123).
GO
GO

/* ==========================================================================================
   PHẦN 3 – 15 CÂY VỢT ĐẦU TIÊN
   Nguồn: docs/seed_san_pham.sql. Mỗi sản phẩm có ảnh SVG trong static/images/rackets/,
   biến thể màu x trọng lượng (2U-5U) x cỡ cán (G3-G6). Chạy lại nhiều lần vẫn cho cùng kết quả
   vì script xoá đúng 15 mã này trước khi nạp.
   ========================================================================================== */
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRAN;

DECLARE @codes TABLE (product_code VARCHAR(30) PRIMARY KEY);
INSERT INTO @codes (product_code) VALUES
    ('YX-AX100ZZ'), ('YX-AX88DP'), ('YX-NF800'), ('YX-ARC11P'),
    ('LN-HLBT9000'), ('LN-AXF90T'), ('LN-WS78'),
    ('VC-TKRYUGA2'), ('VC-ARS90K2'), ('VC-JS12SE'),
    ('MZ-AS7'), ('MZ-ALT01S'),
    ('KP-PS9900'), ('AP-ZZIGGLER2'), ('AP-FW55');

/* Xoá dữ liệu seed cũ (nếu chạy lần 2). Thứ tự: đánh giá → ảnh → biến thể → sản phẩm,
   vì các bảng con đều là FK NO ACTION tới products. */
DELETE r FROM product_reviews  r JOIN products p ON p.id = r.product_id JOIN @codes c ON c.product_code = p.product_code;
DELETE i FROM product_images   i JOIN products p ON p.id = i.product_id JOIN @codes c ON c.product_code = p.product_code;
DELETE v FROM product_variants v JOIN products p ON p.id = v.product_id JOIN @codes c ON c.product_code = p.product_code;
DELETE p FROM products         p JOIN @codes c ON c.product_code = p.product_code;

/* ---- Khoá ngoại tra theo tên (không hard-code id) ---- */
DECLARE @yonex   INT = (SELECT id FROM brands WHERE name = N'Yonex'   AND is_deleted = 0);
DECLARE @lining  INT = (SELECT id FROM brands WHERE name = N'Li-Ning' AND is_deleted = 0);
DECLARE @victor  INT = (SELECT id FROM brands WHERE name = N'Victor'  AND is_deleted = 0);
DECLARE @mizuno  INT = (SELECT id FROM brands WHERE name = N'Mizuno'  AND is_deleted = 0);
DECLARE @kumpoo  INT = (SELECT id FROM brands WHERE name = N'Kumpoo'  AND is_deleted = 0);
DECLARE @apacs   INT = (SELECT id FROM brands WHERE name = N'Apacs'   AND is_deleted = 0);

DECLARE @tanCong   INT = (SELECT id FROM categories WHERE name = N'Vợt tấn công'  AND is_deleted = 0);
DECLARE @phongThu  INT = (SELECT id FROM categories WHERE name = N'Vợt phòng thủ' AND is_deleted = 0);
DECLARE @toanDien  INT = (SELECT id FROM categories WHERE name = N'Vợt toàn diện' AND is_deleted = 0);

IF @yonex IS NULL OR @lining IS NULL OR @victor IS NULL OR @mizuno IS NULL OR @kumpoo IS NULL OR @apacs IS NULL
   OR @tanCong IS NULL OR @phongThu IS NULL OR @toanDien IS NULL
BEGIN
    RAISERROR (N'Thiếu dữ liệu tra cứu: cần có 6 hãng (Yonex, Li-Ning, Victor, Mizuno, Kumpoo, Apacs) và 3 loại vợt (tấn công, phòng thủ, toàn diện).', 16, 1);
    ROLLBACK TRAN;
    RETURN;
END

DECLARE @p INT;

/* =========================== 1. Yonex Astrox 100 ZZ =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('YX-AX100ZZ', N'Vợt cầu lông Yonex Astrox 100 ZZ', N'Dòng vợt tấn công cao cấp nhất của Yonex với khung Namd siêu cứng và thân Hyper Slim cho ra đường cầu cắm sân. Phù hợp người chơi tấn công, đã quen vợt nặng đầu.',
        @yonex, @tanCong, 675, 305, 'HEAD_HEAVY', 'EXTRA_STIFF', 13.5,
        N'Namd (graphite + ống nano carbon)', N'Namd + Super Slim Shaft', 'ACTIVE', 0,
        DATEADD(HOUR, -1, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/yx-ax100zz-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/yx-ax100zz-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'YX-AX100ZZ-01', N'Xanh đen',  '4U', 'G5', 5190000, 4690000, 12, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-AX100ZZ-02', N'Đỏ đô',    '3U', 'G5', 5190000, 4690000,  6, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-AX100ZZ-03', N'Trắng bạc', '4U', 'G4', 4990000, 4490000,  0, 'INACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 2. Yonex Astrox 88D Pro =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('YX-AX88DP', N'Vợt cầu lông Yonex Astrox 88D Pro', N'Phiên bản Pro của Astrox 88D: nặng đầu vừa phải, thân cứng, đập cầu uy lực nhưng vẫn kiểm soát tốt ở lưới. Lựa chọn quen thuộc của các tay vợt đôi tấn công.',
        @yonex, @tanCong, 675, 300, 'HEAD_HEAVY', 'STIFF', 13.0,
        N'Namd (graphite + ống nano carbon)', N'Namd', 'ACTIVE', 0,
        DATEADD(HOUR, -2, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/yx-ax88dp-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/yx-ax88dp-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'YX-AX88DP-01', N'Xanh lam', '4U', 'G5', NULL, 4290000, 12, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-AX88DP-02', N'Đen',      '3U', 'G5', NULL, 4290000,  1, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-AX88DP-03', N'Đồng',     '4U', 'G5', NULL, 4390000,  0, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 3. Yonex Nanoflare 800 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('YX-NF800', N'Vợt cầu lông Yonex Nanoflare 800', N'Vợt nhẹ đầu, thân siêu mảnh cho tốc độ vung vợt nhanh, phòng thủ và phản tạt rất tốt. Dành cho lối đánh nhanh, phòng thủ phản công.',
        @yonex, @phongThu, 675, 285, 'HEAD_LIGHT', 'STIFF', 12.5,
        N'M40X graphite', N'Ultra Slim Shaft (graphite)', 'ACTIVE', 0,
        DATEADD(HOUR, -3, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/yx-nf800-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'YX-NF800-01', N'Xanh ngọc', '4U', 'G5', 4190000, 3990000, 9, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-NF800-02', N'Đỏ',        '4U', 'G5', 4190000, 3990000, 4, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-NF800-03', N'Hồng',      '3U', 'G6', 4190000, 3990000, 2, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 4. Yonex Arcsaber 11 Pro =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('YX-ARC11P', N'Vợt cầu lông Yonex Arcsaber 11 Pro', N'Vợt cân bằng kinh điển: khung giữ form giúp đường cầu chính xác, phù hợp lối đánh kiểm soát, đôi nam nữ và người mới lên trình.',
        @yonex, @toanDien, 675, 295, 'EVEN', 'MEDIUM', 12.5,
        N'Graphite + Namd', N'Namd', 'ACTIVE', 0,
        DATEADD(HOUR, -4, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/yx-arc11p-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'YX-ARC11P-01', N'Xanh dương', '4U', 'G5', NULL, 4190000, 10, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-ARC11P-02', N'Bạc',        '3U', 'G5', NULL, 4190000,  0, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 5. Li-Ning Halbertec 9000 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('LN-HLBT9000', N'Vợt cầu lông Li-Ning Halbertec 9000', N'Vợt tấn công đầu bảng của Li-Ning với khung khí động học và thân siêu cứng, cho lực đập rất mạnh. Khuyến nghị cho người có lực cổ tay tốt.',
        @lining, @tanCong, 675, 305, 'HEAD_HEAVY', 'EXTRA_STIFF', 13.5,
        N'TB Nano + khung khí động học', N'Ultra High Density Carbon', 'ACTIVE', 0,
        DATEADD(HOUR, -5, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/ln-hlbt9000-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/ln-hlbt9000-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'LN-HLBT9000-01', N'Đen nhám', '4U', 'G5', 4990000, 4590000, 5, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'LN-HLBT9000-02', N'Đen nhám', '3U', 'G5', 4990000, 4590000, 3, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 6. Li-Ning Axforce 90 Tiger =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('LN-AXF90T', N'Vợt cầu lông Li-Ning Axforce 90 Tiger', N'Thiết kế hoạ tiết hổ, nặng đầu và thân cứng vừa – cây vợt tấn công quốc dân tầm giá 3,5 triệu. Phù hợp cả đánh đơn và đánh đôi.',
        @lining, @tanCong, 675, 300, 'HEAD_HEAVY', 'STIFF', 13.0,
        N'Military-grade carbon', N'TB Nano', 'ACTIVE', 0,
        DATEADD(HOUR, -6, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/ln-axf90t-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'LN-AXF90T-01', N'Đỏ',        '4U', 'G5', NULL, 3590000, 11, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'LN-AXF90T-02', N'Xanh rêu',  '4U', 'G5', NULL, 3590000,  7, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'LN-AXF90T-03', N'Đen',       '3U', 'G5', NULL, 3590000,  2, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 7. Li-Ning Windstorm 78 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('LN-WS78', N'Vợt cầu lông Li-Ning Windstorm 78', N'Vợt nhẹ, dễ đánh, giá mềm – rất hợp cho người mới chơi hoặc phong trào cần cây vợt bền, dễ làm quen.',
        @lining, @phongThu, 675, 285, 'HEAD_LIGHT', 'MEDIUM', 11.5,
        N'Graphite', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -7, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/ln-ws78-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'LN-WS78-01', N'Xanh dương', '4U', 'G5', 1890000, 1690000, 15, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'LN-WS78-02', N'Trắng',      '5U', 'G5', 1890000, 1690000,  8, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 8. Victor Thruster Ryuga II =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('VC-TKRYUGA2', N'Vợt cầu lông Victor Thruster Ryuga II', N'Vợt tấn công nặng đầu của Victor với công nghệ Hard Cored tạo độ ổn định khi đập mạnh. Dòng vợt được nhiều tuyển thủ đôi nam tin dùng.',
        @victor, @tanCong, 675, 306, 'HEAD_HEAVY', 'EXTRA_STIFF', 13.5,
        N'Pyrofil + Hard Cored', N'Pyrofil', 'ACTIVE', 0,
        DATEADD(HOUR, -8, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/vc-tkryuga2-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/vc-tkryuga2-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'VC-TKRYUGA2-01', N'Đen kim tuyến', '4U', 'G5', NULL, 4350000, 0, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-TKRYUGA2-02', N'Đỏ',           '3U', 'G5', NULL, 4350000, 0, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-TKRYUGA2-03', N'Xanh',         '4U', 'G6', NULL, 4350000, 0, 'INACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 9. Victor Auraspeed 90K II =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('VC-ARS90K2', N'Vợt cầu lông Victor Auraspeed 90K II', N'Cân bằng thiên công, thân cứng vừa cho tốc độ vung tay nhanh mà vẫn đủ lực dứt điểm. Lựa chọn linh hoạt cho người chơi đa phong cách.',
        @victor, @toanDien, 675, 292, 'EVEN', 'STIFF', 12.5,
        N'Pyrofil', N'Pyrofil + thân cứng', 'ACTIVE', 0,
        DATEADD(HOUR, -9, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/vc-ars90k2-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'VC-ARS90K2-01', N'Xanh đen', '4U', 'G5', 4150000, 3890000, 9, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-ARS90K2-02', N'Trắng',    '4U', 'G5', 4150000, 3890000, 5, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-ARS90K2-03', N'Đỏ',       '3U', 'G5', 4150000, 3890000, 3, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 10. Victor JetSpeed 12 SE =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('VC-JS12SE', N'Vợt cầu lông Victor JetSpeed 12 SE', N'Bản đặc biệt của dòng JetSpeed: khung mỏng giảm lực cản không khí, hỗ trợ phòng thủ và phản tạt nhanh. Dễ chơi với người trình trung bình.',
        @victor, @toanDien, 675, 288, 'EVEN', 'MEDIUM', 12.0,
        N'Graphite khung mỏng', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -10, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/vc-js12se-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'VC-JS12SE-01', N'Xanh navy', '4U', 'G5', NULL, 2890000, 10, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-JS12SE-02', N'Đen',       '4U', 'G5', NULL, 2890000,  6, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 11. Mizuno Acrospeed 7 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('MZ-AS7', N'Vợt cầu lông Mizuno Acrospeed 7', N'Vợt cân bằng dễ đánh của Mizuno, cảm giác cầu êm tay và kiểm soát tốt ở lưới. Phù hợp người chơi phong trào muốn cây vợt đa dụng.',
        @mizuno, @toanDien, 675, 290, 'EVEN', 'MEDIUM', 12.0,
        N'Graphite', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -11, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/mz-as7-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'MZ-AS7-01', N'Xanh', '4U', 'G5', 2890000, 2590000, 12, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'MZ-AS7-02', N'Đen',  '4U', 'G5', 2890000, 2590000,  4, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 12. Mizuno Altius 01 Speed =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('MZ-ALT01S', N'Vợt cầu lông Mizuno Altius 01 Speed', N'Vợt nhẹ đầu hướng tốc độ, phù hợp đánh đôi và lối chơi phòng thủ phản công. Thân cứng vừa nên vẫn đủ lực khi cần bung cầu.',
        @mizuno, @phongThu, 675, 285, 'HEAD_LIGHT', 'STIFF', 11.5,
        N'Graphite', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -12, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/mz-alt01s-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'MZ-ALT01S-01', N'Bạc',       '4U', 'G5', NULL, 2190000, 7, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'MZ-ALT01S-02', N'Xanh ngọc', '4U', 'G5', NULL, 2190000, 0, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 13. Kumpoo Power Shot 9900 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('KP-PS9900', N'Vợt cầu lông Kumpoo Power Shot 9900', N'Vợt tấn công giá rẻ đáng chơi: nặng đầu, thân cứng, cho lực đập tốt trong tầm giá dưới 2 triệu.',
        @kumpoo, @tanCong, 675, 300, 'HEAD_HEAVY', 'STIFF', 12.0,
        N'Graphite', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -13, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/kp-ps9900-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'KP-PS9900-01', N'Vàng đen', '4U', 'G5', 2190000, 1890000, 13, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'KP-PS9900-02', N'Đỏ đen',   '3U', 'G5', 2190000, 1890000,  5, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 14. Apacs Z-Ziggler 2 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('AP-ZZIGGLER2', N'Vợt cầu lông Apacs Z-Ziggler 2', N'Vợt tấn công phổ thông bán chạy của Apacs: nặng đầu, khung chắc, chịu được mức căng cao trong tầm giá rẻ.',
        @apacs, @tanCong, 675, 300, 'HEAD_HEAVY', 'STIFF', 12.5,
        N'Graphite dệt Woven', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -14, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/ap-zziggler2-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'AP-ZZIGGLER2-01', N'Đen vàng',  '4U', 'G5', NULL, 1290000, 14, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'AP-ZZIGGLER2-02', N'Trắng xanh', '3U', 'G5', NULL, 1290000,  6, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 15. Apacs Feather Weight 55 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('AP-FW55', N'Vợt cầu lông Apacs Feather Weight 55', N'Cây vợt siêu nhẹ (khoảng 55 gram) dễ vung, phù hợp người mới, nữ chơi phong trào hoặc cần vợt nhẹ cho cổ tay yếu.',
        @apacs, @phongThu, 675, 280, 'HEAD_LIGHT', 'MEDIUM', 11.0,
        N'Graphite siêu nhẹ', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -15, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/ap-fw55-1.svg', 1, 0, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'AP-FW55-01', N'Trắng', '5U', 'G5', 1190000, 990000, 16, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'AP-FW55-02', N'Hồng',  '5U', 'G5', 1190000, 990000,  9, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'AP-FW55-03', N'Đen',   '4U', 'G5', 1190000, 990000,  0, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

COMMIT TRAN;

/* ---- Báo cáo kết quả nạp ---- */
SELECT N'Tổng sản phẩm đang bán' AS muc, COUNT(*) AS so_luong FROM products WHERE is_deleted = 0 AND status = 'ACTIVE'
UNION ALL SELECT N'Biến thể đang bán', COUNT(*) FROM product_variants v JOIN products p ON p.id = v.product_id WHERE v.status = 'ACTIVE' AND p.is_deleted = 0
UNION ALL SELECT N'Biến thể đã ngừng bán (khách không thấy)', COUNT(*) FROM product_variants WHERE status = 'INACTIVE'
UNION ALL SELECT N'Ảnh sản phẩm', COUNT(*) FROM product_images
UNION ALL SELECT N'Sản phẩm tạm hết hàng (mọi biến thể tồn = 0)', COUNT(*) FROM products p WHERE p.is_deleted = 0 AND p.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND v.stock_quantity > 0);
GO

/* ==========================================================================================
   PHẦN 4 – 5 CÂY VỢT BỔ SUNG (ĐỦ 20 MẪU)
   Nguồn: docs/seed_san_pham_bo_sung.sql. Chỉ xoá/nạp 5 mã bên dưới, không đụng 15 mã của PHẦN 3.
   ========================================================================================== */
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRAN;

DECLARE @codes TABLE (product_code VARCHAR(30) PRIMARY KEY);
INSERT INTO @codes (product_code) VALUES
    ('YX-AX99P'), ('YX-NF1000Z'), ('LN-AXF100'), ('VC-TKFALCON'), ('MZ-FT11P');

/* Xoá dữ liệu cũ của CHÍNH 5 mã này (nếu chạy lần 2): đánh giá → ảnh → biến thể → sản phẩm */
DELETE r FROM product_reviews  r JOIN products p ON p.id = r.product_id JOIN @codes c ON c.product_code = p.product_code;
DELETE i FROM product_images   i JOIN products p ON p.id = i.product_id JOIN @codes c ON c.product_code = p.product_code;
DELETE v FROM product_variants v JOIN products p ON p.id = v.product_id JOIN @codes c ON c.product_code = p.product_code;
DELETE p FROM products         p JOIN @codes c ON c.product_code = p.product_code;

/* ---- Khoá ngoại tra theo tên (không hard-code id) ---- */
DECLARE @yonex   INT = (SELECT id FROM brands WHERE name = N'Yonex'   AND is_deleted = 0);
DECLARE @lining  INT = (SELECT id FROM brands WHERE name = N'Li-Ning' AND is_deleted = 0);
DECLARE @victor  INT = (SELECT id FROM brands WHERE name = N'Victor'  AND is_deleted = 0);
DECLARE @mizuno  INT = (SELECT id FROM brands WHERE name = N'Mizuno'  AND is_deleted = 0);

DECLARE @tanCong   INT = (SELECT id FROM categories WHERE name = N'Vợt tấn công'  AND is_deleted = 0);
DECLARE @phongThu  INT = (SELECT id FROM categories WHERE name = N'Vợt phòng thủ' AND is_deleted = 0);
DECLARE @toanDien  INT = (SELECT id FROM categories WHERE name = N'Vợt toàn diện' AND is_deleted = 0);

IF @yonex IS NULL OR @lining IS NULL OR @victor IS NULL OR @mizuno IS NULL
   OR @tanCong IS NULL OR @phongThu IS NULL OR @toanDien IS NULL
BEGIN
    RAISERROR (N'Thiếu dữ liệu tra cứu: cần 4 hãng (Yonex, Li-Ning, Victor, Mizuno) và 3 loại vợt.', 16, 1);
    ROLLBACK TRAN;
    RETURN;
END

DECLARE @p INT;

/* =========================== 16. Yonex Astrox 99 Pro =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('YX-AX99P', N'Vợt cầu lông Yonex Astrox 99 Pro', N'Vợt tấn công cho người đập cầu mạnh: khung Namd kết hợp Rotational Generator System giữ ổn định khi vung nhanh, thân cứng vừa dễ làm quen hơn Astrox 100 ZZ.',
        @yonex, @tanCong, 675, 310, 'HEAD_HEAVY', 'STIFF', 13.0,
        N'Namd + graphite', N'Namd', 'ACTIVE', 0,
        DATEADD(HOUR, -16, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/yx-ax99p-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/yx-ax99p-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'YX-AX99P-01', N'Trắng ngọc',  '4U', 'G5', 4790000, 4290000, 10, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-AX99P-02', N'Đỏ tía',      '4U', 'G4', 4790000, 4290000,  5, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-AX99P-03', N'Xanh tím',    '3U', 'G5', 4990000, 4490000,  0, 'INACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 17. Yonex Nanoflare 1000 Z =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('YX-NF1000Z', N'Vợt cầu lông Yonex Nanoflare 1000 Z', N'Cây vợt nhẹ đầu nhất của Yonex dành cho lối đánh nhanh, phản tạt và đập cầu liên tục; khung Sonic Flare giảm rung, phù hợp đánh đôi tốc độ.',
        @yonex, @phongThu, 675, 290, 'HEAD_LIGHT', 'STIFF', 12.5,
        N'Graphite + M40X', N'Hyper Slim Shaft', 'ACTIVE', 0,
        DATEADD(HOUR, -17, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/yx-nf1000z-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/yx-nf1000z-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'YX-NF1000Z-01', N'Vàng đồng', '4U', 'G5', 5290000, 4790000, 9, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'YX-NF1000Z-02', N'Trắng xanh', '4U', 'G4', 5290000, 4790000, 4, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 18. Li-Ning Axforce 100 =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('LN-AXF100', N'Vợt cầu lông Li-Ning Axforce 100', N'Vợt tấn công đầu bảng của Li-Ning: khung 6 góc giảm xoắn, thân siêu cứng cho sức mạnh tối đa, dành cho người có lực cổ tay tốt.',
        @lining, @tanCong, 675, 312, 'HEAD_HEAVY', 'EXTRA_STIFF', 14.0,
        N'Carbon T1100G', N'Carbon T1100G + UHB', 'ACTIVE', 0,
        DATEADD(HOUR, -18, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/ln-axf100-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/ln-axf100-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'LN-AXF100-01', N'Đen đỏ', '4U', 'G5', 5490000, 4990000, 8, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'LN-AXF100-02', N'Đen vàng', '3U', 'G4', 5590000, 5090000, 3, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 19. Victor Thruster K Falcon =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('VC-TKFALCON', N'Vợt cầu lông Victor Thruster K Falcon', N'Thuộc dòng Thruster nặng đầu của Victor, khung Turbo gọn khí động cho cú đập xoáy; chất vợt cân bằng giữa sức mạnh và tốc độ vung.',
        @victor, @tanCong, 675, 303, 'HEAD_HEAVY', 'STIFF', 13.0,
        N'Graphite + Hard Cored Technology', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -19, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/vc-tkfalcon-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/vc-tkfalcon-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'VC-TKFALCON-01', N'Đen xanh', '4U', 'G5', 3890000, 3490000, 11, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-TKFALCON-02', N'Đen bạc',  '4U', 'G4', 3890000, 3490000,  6, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'VC-TKFALCON-03', N'Xanh dương', '5U', 'G5', 3690000, 3290000, 0, 'INACTIVE', SYSDATETIME(), SYSDATETIME());

/* =========================== 20. Mizuno Fortius 11 Power =========================== */
INSERT INTO products (product_code, name, description, brand_id, category_id, length_mm, balance_point_mm,
                      balance_type, shaft_flexibility, max_tension_kg, frame_material, shaft_material, status,
                      is_deleted, created_at, updated_at)
VALUES ('MZ-FT11P', N'Vợt cầu lông Mizuno Fortius 11 Power', N'Vợt toàn diện hơi nặng đầu của Mizuno: dễ lấy lại thế trận ở cuối sân mà vẫn đủ nhanh khi chắn cầu, phù hợp người chơi phong trào lên trình.',
        @mizuno, @toanDien, 675, 298, 'HEAD_HEAVY', 'MEDIUM', 12.0,
        N'Graphite Japan', N'Graphite', 'ACTIVE', 0,
        DATEADD(HOUR, -20, SYSDATETIME()), SYSDATETIME());
SET @p = SCOPE_IDENTITY();
INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at) VALUES
    (@p, '/images/rackets/mz-ft11p-1.svg', 1, 0, SYSDATETIME()),
    (@p, '/images/rackets/mz-ft11p-2.svg', 0, 1, SYSDATETIME());
INSERT INTO product_variants (product_id, sku, color, weight_class, grip_size, original_price, price, stock_quantity, status, created_at, updated_at) VALUES
    (@p, 'MZ-FT11P-01', N'Xanh lá',  '4U', 'G5', 2590000, 2290000, 13, 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    (@p, 'MZ-FT11P-02', N'Đen',      '4U', 'G4', 2590000, 2290000,  7, 'ACTIVE', SYSDATETIME(), SYSDATETIME());

COMMIT TRAN;

/* ---- Báo cáo kết quả nạp ---- */
SELECT N'Tổng sản phẩm đang bán' AS muc, COUNT(*) AS so_luong FROM products WHERE is_deleted = 0 AND status = 'ACTIVE'
UNION ALL SELECT N'Số hãng', COUNT(*) FROM brands WHERE is_deleted = 0
UNION ALL SELECT N'Biến thể đang bán', COUNT(*) FROM product_variants v JOIN products p ON p.id = v.product_id WHERE v.status = 'ACTIVE' AND p.is_deleted = 0
UNION ALL SELECT N'Ảnh sản phẩm', COUNT(*) FROM product_images;
GO

/* ==========================================================================================
   PHẦN 5 – TÀI KHOẢN ADMIN MẶC ĐỊNH
   Email: admin@badstore.local – mật khẩu: Admin@123 (hash BCrypt bên dưới).
   DataInitializer của ứng dụng thấy email đã tồn tại nên sẽ không tạo lại.
   ========================================================================================== */
USE BadminStore;
GO

IF NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@badstore.local')
BEGIN
    INSERT INTO users (email, password_hash, full_name, phone, avatar, role, status, created_at, updated_at)
    VALUES ('admin@badstore.local',
            '$2a$10$BlCXT9WBBmzG0N0dZtoTKOMl2D0Ilra5eIA3CeJ1updsvTovjYBD6',
            N'Quản trị viên', NULL, NULL, 'ADMIN', 'ACTIVE', SYSDATETIME(), SYSDATETIME());
END
GO

/* ==========================================================================================
   PHẦN 6 – DỮ LIỆU DEMO THỰC TẾ (TUỲ CHỌN)
   Nguồn: docs/seed_demo_thuc_te.sql. Tạo 11 khách (10 ACTIVE + 1 LOCKED), địa chỉ nhiều tỉnh,
   5 voucher, 42 đơn trải 42 ngày đủ trạng thái, thanh toán, lịch sử, 15 đánh giá và 2 món
   trong giỏ của khách Nguyễn Minh Anh.
   - Đổi @seed_demo = 0 nếu chỉ muốn CSDL sạch (20 sản phẩm, không có đơn demo).
   - Phần này RESET đơn hàng/thanh toán/đánh giá/voucher/giỏ hiện có, chỉ dùng cho CSDL demo.
   - Chạy trong một transaction; lỗi sẽ rollback toàn bộ phần demo.
   ========================================================================================== */
DECLARE @seed_demo BIT = 1;  -- 1 = nạp dữ liệu demo, 0 = chỉ giữ CSDL sạch
IF @seed_demo = 1
BEGIN
SET NOCOUNT ON;
SET XACT_ABORT ON;

BEGIN TRY
BEGIN TRAN;

/* ------------------------------------------------------------------------------------------
   0. DANH SÁCH KHÁCH DEMO + DỌN DỮ LIỆU TEST
   ------------------------------------------------------------------------------------------ */
DECLARE @demoUsers TABLE (
    email VARCHAR(255) PRIMARY KEY,
    full_name NVARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    created_days INT NOT NULL,
    status VARCHAR(20) NOT NULL
);

INSERT INTO @demoUsers (email, full_name, phone, created_days, status) VALUES
    ('nguyenminhanh.89@gmail.com',  N'Nguyễn Minh Anh',  '0912345678', 120, 'ACTIVE'),
    ('tranquochuy.92@gmail.com',    N'Trần Quốc Huy',    '0938765432',  95, 'ACTIVE'),
    ('lethuha.95@gmail.com',        N'Lê Thu Hà',        '0905112233',  80, 'ACTIVE'),
    ('phamgiahung.90@gmail.com',    N'Phạm Gia Hùng',    '0977445566',  70, 'ACTIVE'),
    ('hoangmaianh.97@gmail.com',    N'Hoàng Mai Anh',    '0968334455',  60, 'ACTIVE'),
    ('vuducmanh.88@gmail.com',      N'Vũ Đức Mạnh',      '0913556677',  50, 'ACTIVE'),
    ('dangthuylinh.96@gmail.com',   N'Đặng Thùy Linh',   '0982667788',  40, 'ACTIVE'),
    ('buivankhoa.93@gmail.com',     N'Bùi Văn Khoa',     '0904778899',  30, 'ACTIVE'),
    ('ngothanhha.91@gmail.com',     N'Ngô Thanh Hà',     '0939889900',  20, 'ACTIVE'),
    ('dovanthang.94@gmail.com',     N'Đỗ Văn Thắng',     '0918990011',  10, 'ACTIVE'),
    ('nguyenhoangnam.87@gmail.com', N'Nguyễn Hoàng Nam', '0903112244', 200, 'LOCKED');

/* Xóa đơn hàng/cart/đánh giá/voucher cũ. Thứ tự con -> cha. */
DELETE FROM product_reviews;
DELETE FROM order_status_histories;
DELETE FROM payments;
DELETE FROM order_items;
DELETE FROM orders;
DELETE FROM cart_items;
DELETE FROM vouchers;

/* Xóa khách demo của lần chạy trước (nếu có). */
DELETE ua
FROM user_addresses ua
JOIN users u ON u.id = ua.user_id
JOIN @demoUsers d ON d.email = u.email;

DELETE u
FROM users u
JOIN @demoUsers d ON d.email = u.email;

/* Xóa các tài khoản test rõ ràng, giữ admin và các email Gmail thật. */
DELETE ua
FROM user_addresses ua
JOIN users u ON u.id = ua.user_id
WHERE u.email LIKE '%@example.com'
   OR u.email LIKE 'uitest%'
   OR u.email LIKE 'gio.hang.%'
   OR u.email LIKE 'smoke.%';

DELETE FROM users
WHERE email LIKE '%@example.com'
   OR email LIKE 'uitest%'
   OR email LIKE 'gio.hang.%'
   OR email LIKE 'smoke.%';

/* 20 mã sản phẩm thật. Mọi sản phẩm khác là dữ liệu test và sẽ bị xóa. */
DECLARE @realCodes TABLE (product_code VARCHAR(30) PRIMARY KEY);
INSERT INTO @realCodes (product_code) VALUES
    ('YX-AX100ZZ'), ('YX-AX88DP'), ('YX-NF800'), ('YX-ARC11P'),
    ('LN-HLBT9000'), ('LN-AXF90T'), ('LN-WS78'),
    ('VC-TKRYUGA2'), ('VC-ARS90K2'), ('VC-JS12SE'),
    ('MZ-AS7'), ('MZ-ALT01S'),
    ('KP-PS9900'), ('AP-ZZIGGLER2'), ('AP-FW55'),
    ('YX-AX99P'), ('YX-NF1000Z'), ('LN-AXF100'),
    ('VC-TKFALCON'), ('MZ-FT11P');

DELETE pi
FROM product_images pi
JOIN products p ON p.id = pi.product_id
WHERE NOT EXISTS (SELECT 1 FROM @realCodes c WHERE c.product_code = p.product_code);

DELETE pv
FROM product_variants pv
JOIN products p ON p.id = pv.product_id
WHERE NOT EXISTS (SELECT 1 FROM @realCodes c WHERE c.product_code = p.product_code);

DELETE p
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM @realCodes c WHERE c.product_code = p.product_code);

/* Đưa 20 sản phẩm thật về trạng thái hiển thị. */
UPDATE p
SET p.status = 'ACTIVE',
    p.is_deleted = 0,
    p.updated_at = SYSDATETIME()
FROM products p
JOIN @realCodes c ON c.product_code = p.product_code;

/* ------------------------------------------------------------------------------------------
   1. ẢNH SẢN PHẨM: 2 ảnh SVG cho mỗi cây vợt
   ------------------------------------------------------------------------------------------ */
DECLARE @imageSeed TABLE (
    product_code VARCHAR(30) PRIMARY KEY,
    image1 VARCHAR(255) NOT NULL,
    image2 VARCHAR(255) NOT NULL
);

INSERT INTO @imageSeed (product_code, image1, image2) VALUES
    ('YX-AX100ZZ',   '/images/rackets/yx-ax100zz-1.svg',   '/images/rackets/yx-ax100zz-2.svg'),
    ('YX-AX88DP',    '/images/rackets/yx-ax88dp-1.svg',    '/images/rackets/yx-ax88dp-2.svg'),
    ('YX-NF800',     '/images/rackets/yx-nf800-1.svg',     '/images/rackets/yx-nf800-2.svg'),
    ('YX-ARC11P',    '/images/rackets/yx-arc11p-1.svg',    '/images/rackets/yx-arc11p-2.svg'),
    ('LN-HLBT9000',  '/images/rackets/ln-hlbt9000-1.svg',  '/images/rackets/ln-hlbt9000-2.svg'),
    ('LN-AXF90T',    '/images/rackets/ln-axf90t-1.svg',    '/images/rackets/ln-axf90t-2.svg'),
    ('LN-WS78',      '/images/rackets/ln-ws78-1.svg',      '/images/rackets/ln-ws78-2.svg'),
    ('VC-TKRYUGA2',  '/images/rackets/vc-tkryuga2-1.svg',  '/images/rackets/vc-tkryuga2-2.svg'),
    ('VC-ARS90K2',   '/images/rackets/vc-ars90k2-1.svg',   '/images/rackets/vc-ars90k2-2.svg'),
    ('VC-JS12SE',    '/images/rackets/vc-js12se-1.svg',    '/images/rackets/vc-js12se-2.svg'),
    ('MZ-AS7',       '/images/rackets/mz-as7-1.svg',       '/images/rackets/mz-as7-2.svg'),
    ('MZ-ALT01S',    '/images/rackets/mz-alt01s-1.svg',    '/images/rackets/mz-alt01s-2.svg'),
    ('KP-PS9900',    '/images/rackets/kp-ps9900-1.svg',    '/images/rackets/kp-ps9900-2.svg'),
    ('AP-ZZIGGLER2', '/images/rackets/ap-zziggler2-1.svg', '/images/rackets/ap-zziggler2-2.svg'),
    ('AP-FW55',      '/images/rackets/ap-fw55-1.svg',      '/images/rackets/ap-fw55-2.svg'),
    ('YX-AX99P',     '/images/rackets/yx-ax99p-1.svg',     '/images/rackets/yx-ax99p-2.svg'),
    ('YX-NF1000Z',   '/images/rackets/yx-nf1000z-1.svg',   '/images/rackets/yx-nf1000z-2.svg'),
    ('LN-AXF100',    '/images/rackets/ln-axf100-1.svg',    '/images/rackets/ln-axf100-2.svg'),
    ('VC-TKFALCON',  '/images/rackets/vc-tkfalcon-1.svg',  '/images/rackets/vc-tkfalcon-2.svg'),
    ('MZ-FT11P',     '/images/rackets/mz-ft11p-1.svg',     '/images/rackets/mz-ft11p-2.svg');

DELETE pi
FROM product_images pi
JOIN products p ON p.id = pi.product_id
JOIN @realCodes c ON c.product_code = p.product_code;

INSERT INTO product_images (product_id, image_url, is_primary, sort_order, created_at)
SELECT p.id, i.image1, 1, 0, SYSDATETIME()
FROM @imageSeed i
JOIN products p ON p.product_code = i.product_code
UNION ALL
SELECT p.id, i.image2, 0, 1, SYSDATETIME()
FROM @imageSeed i
JOIN products p ON p.product_code = i.product_code;

/* ------------------------------------------------------------------------------------------
   2. BIẾN THỂ: giá niêm yết/giá bán/tồn kho chuẩn theo 20 mẫu vợt
   ------------------------------------------------------------------------------------------ */
DECLARE @variantSeed TABLE (
    sku VARCHAR(30) PRIMARY KEY,
    original_price DECIMAL(18,0) NULL,
    price DECIMAL(18,0) NOT NULL,
    stock_quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL
);

INSERT INTO @variantSeed (sku, original_price, price, stock_quantity, status) VALUES
    ('YX-AX100ZZ-01',   5190000, 4690000, 12, 'ACTIVE'),
    ('YX-AX100ZZ-02',   5190000, 4690000,  6, 'ACTIVE'),
    ('YX-AX100ZZ-03',   4990000, 4490000,  0, 'INACTIVE'),
    ('YX-AX88DP-01',    NULL,    4290000, 12, 'ACTIVE'),
    ('YX-AX88DP-02',    NULL,    4290000,  6, 'ACTIVE'),
    ('YX-AX88DP-03',    NULL,    4390000,  0, 'ACTIVE'),
    ('YX-NF800-01',     4190000, 3990000,  9, 'ACTIVE'),
    ('YX-NF800-02',     4190000, 3990000,  4, 'ACTIVE'),
    ('YX-NF800-03',     4190000, 3990000,  2, 'ACTIVE'),
    ('YX-ARC11P-01',    NULL,    4190000, 10, 'ACTIVE'),
    ('YX-ARC11P-02',    NULL,    4190000,  0, 'ACTIVE'),
    ('LN-HLBT9000-01',  4990000, 4590000,  5, 'ACTIVE'),
    ('LN-HLBT9000-02',  4990000, 4590000,  3, 'ACTIVE'),
    ('LN-AXF90T-01',    NULL,    3590000, 10, 'ACTIVE'),
    ('LN-AXF90T-02',    NULL,    3590000,  7, 'ACTIVE'),
    ('LN-AXF90T-03',    NULL,    3590000,  2, 'ACTIVE'),
    ('LN-WS78-01',      1890000, 1690000, 15, 'ACTIVE'),
    ('LN-WS78-02',      1890000, 1690000,  8, 'ACTIVE'),
    ('VC-TKRYUGA2-01',  NULL,    4350000,  8, 'ACTIVE'),
    ('VC-TKRYUGA2-02',  NULL,    4350000,  5, 'ACTIVE'),
    ('VC-TKRYUGA2-03',  NULL,    4350000,  0, 'INACTIVE'),
    ('VC-ARS90K2-01',   4150000, 3890000,  9, 'ACTIVE'),
    ('VC-ARS90K2-02',   4150000, 3890000,  5, 'ACTIVE'),
    ('VC-ARS90K2-03',   4150000, 3890000,  3, 'ACTIVE'),
    ('VC-JS12SE-01',    NULL,    2890000,  9, 'ACTIVE'),
    ('VC-JS12SE-02',    NULL,    2890000,  6, 'ACTIVE'),
    ('MZ-AS7-01',       2890000, 2590000, 12, 'ACTIVE'),
    ('MZ-AS7-02',       2890000, 2590000,  4, 'ACTIVE'),
    ('MZ-ALT01S-01',    NULL,    2190000,  7, 'ACTIVE'),
    ('MZ-ALT01S-02',    NULL,    2190000,  0, 'ACTIVE'),
    ('KP-PS9900-01',    2190000, 1890000, 13, 'ACTIVE'),
    ('KP-PS9900-02',    2190000, 1890000,  5, 'ACTIVE'),
    ('AP-ZZIGGLER2-01', NULL,    1290000, 13, 'ACTIVE'),
    ('AP-ZZIGGLER2-02', NULL,    1290000,  5, 'ACTIVE'),
    ('AP-FW55-01',      1190000,  990000, 15, 'ACTIVE'),
    ('AP-FW55-02',      1190000,  990000,  7, 'ACTIVE'),
    ('AP-FW55-03',      1190000,  990000,  0, 'ACTIVE'),
    ('YX-AX99P-01',     4790000, 4290000, 10, 'ACTIVE'),
    ('YX-AX99P-02',     4790000, 4290000,  5, 'ACTIVE'),
    ('YX-AX99P-03',     4990000, 4490000,  0, 'INACTIVE'),
    ('YX-NF1000Z-01',   5290000, 4790000,  9, 'ACTIVE'),
    ('YX-NF1000Z-02',   5290000, 4790000,  4, 'ACTIVE'),
    ('LN-AXF100-01',    5490000, 4990000,  8, 'ACTIVE'),
    ('LN-AXF100-02',    5590000, 5090000,  3, 'ACTIVE'),
    ('VC-TKFALCON-01',  3890000, 3490000, 11, 'ACTIVE'),
    ('VC-TKFALCON-02',  3890000, 3490000,  6, 'ACTIVE'),
    ('VC-TKFALCON-03',  3690000, 3290000,  0, 'INACTIVE'),
    ('MZ-FT11P-01',     2590000, 2290000, 13, 'ACTIVE'),
    ('MZ-FT11P-02',     2590000, 2290000,  7, 'ACTIVE');

IF EXISTS (SELECT 1
           FROM @variantSeed s
           LEFT JOIN product_variants v ON v.sku = s.sku
           WHERE v.id IS NULL)
    THROW 50001, N'Thiếu biến thể thật trong CSDL. Hãy chạy lại docs/seed_san_pham.sql và docs/seed_san_pham_bo_sung.sql trước.', 1;

UPDATE v
SET v.original_price = s.original_price,
    v.price = s.price,
    v.stock_quantity = s.stock_quantity,
    v.status = s.status,
    v.updated_at = SYSDATETIME()
FROM product_variants v
JOIN @variantSeed s ON s.sku = v.sku;

IF (SELECT COUNT(*)
    FROM product_variants v
    JOIN products p ON p.id = v.product_id
    JOIN @realCodes c ON c.product_code = p.product_code) <> (SELECT COUNT(*) FROM @variantSeed)
    THROW 50002, N'Số biến thể thật không khớp 49. Kiểm tra lại dữ liệu sản phẩm trước khi chạy.', 1;

/* ------------------------------------------------------------------------------------------
   3. KHÁCH HÀNG + ĐỊA CHỈ DEMO
   ------------------------------------------------------------------------------------------ */
DECLARE @demoPasswordHash VARCHAR(100) = '$2a$10$0asK93.06e98jlKQlgiD6e.OHWCDZvvb/QVNHl/EbeehFtgN3Xgme'; -- Demo@123

INSERT INTO users (email, password_hash, full_name, phone, avatar, role, status, created_at, updated_at)
SELECT d.email, @demoPasswordHash, d.full_name, d.phone, NULL, 'CUSTOMER', d.status,
       DATEADD(DAY, -d.created_days, SYSDATETIME()), SYSDATETIME()
FROM @demoUsers d;

DECLARE @addressSeed TABLE (
    email VARCHAR(255) NOT NULL,
    receiver_name NVARCHAR(100) NOT NULL,
    receiver_phone VARCHAR(20) NOT NULL,
    province_name NVARCHAR(100) NOT NULL,
    district_name NVARCHAR(100) NOT NULL,
    ward_name NVARCHAR(100) NOT NULL,
    address_detail NVARCHAR(255) NOT NULL,
    is_default BIT NOT NULL
);

INSERT INTO @addressSeed (email, receiver_name, receiver_phone, province_name, district_name, ward_name, address_detail, is_default) VALUES
    ('nguyenminhanh.89@gmail.com',  N'Nguyễn Minh Anh',  '0912345678', N'Hà Nội',          N'Quận Cầu Giấy',     N'Phường Yên Hòa',       N'24 Ngõ 12 Nguyễn Khang', 1),
    ('nguyenminhanh.89@gmail.com',  N'Nguyễn Minh Anh',  '0912345678', N'Hà Nội',          N'Quận Thanh Xuân',   N'Phường Thanh Xuân Trung', N'88 Nguyễn Trãi, tòa nhà B', 0),
    ('tranquochuy.92@gmail.com',    N'Trần Quốc Huy',    '0938765432', N'TP. Hồ Chí Minh', N'Thành phố Thủ Đức', N'Phường Bình Thọ',      N'78 Đường số 5, KDC Hồng Long', 1),
    ('lethuha.95@gmail.com',        N'Lê Thu Hà',        '0905112233', N'Đà Nẵng',         N'Quận Hải Châu',     N'Phường Nam Dương',     N'15 Nguyễn Văn Linh', 1),
    ('phamgiahung.90@gmail.com',    N'Phạm Gia Hùng',    '0977445566', N'Hải Phòng',       N'Quận Hải An',       N'Phường Đằng Lâm',      N'36 Lê Hồng Phong', 1),
    ('hoangmaianh.97@gmail.com',    N'Hoàng Mai Anh',    '0968334455', N'Gia Lai',         N'TP. Pleiku',        N'Phường Diên Hồng',     N'102 Trần Phú', 1),
    ('vuducmanh.88@gmail.com',      N'Vũ Đức Mạnh',      '0913556677', N'Thái Nguyên',     N'TP. Thái Nguyên',   N'Phường Quang Trung',   N'45 Hùng Vương', 1),
    ('dangthuylinh.96@gmail.com',   N'Đặng Thùy Linh',   '0982667788', N'Hà Nội',          N'Quận Thanh Xuân',   N'Phường Thanh Xuân Trung', N'21 Lý Thường Kiệt', 1),
    ('buivankhoa.93@gmail.com',     N'Bùi Văn Khoa',     '0904778899', N'Hà Nội',          N'Quận Hoàn Kiếm',    N'Phường Cửa Nam',       N'56 Nguyễn Văn Cừ', 1),
    ('ngothanhha.91@gmail.com',     N'Ngô Thanh Hà',     '0939889900', N'Cần Thơ',         N'Quận Ninh Kiều',    N'Phường An Hòa',        N'12 Đồng Khởi', 1),
    ('dovanthang.94@gmail.com',     N'Đỗ Văn Thắng',     '0918990011', N'TP. Hồ Chí Minh', N'Quận 1',            N'Phường Sài Gòn',       N'25 Lê Thánh Tôn', 1);

INSERT INTO user_addresses (user_id, receiver_name, receiver_phone, province_id, province_name,
                            district_id, district_name, ward_code, ward_name, address_detail,
                            is_default, is_deleted, created_at, updated_at)
SELECT u.id, a.receiver_name, a.receiver_phone, NULL, a.province_name,
       NULL, a.district_name, NULL, a.ward_name, a.address_detail,
       a.is_default, 0, DATEADD(DAY, -30, SYSDATETIME()), SYSDATETIME()
FROM @addressSeed a
JOIN users u ON u.email = a.email;

/* ------------------------------------------------------------------------------------------
   4. VOUCHER THỰC TẾ
   ------------------------------------------------------------------------------------------ */
INSERT INTO vouchers (code, name, description, discount_type, discount_value, min_order_value,
                      max_discount_amount, quantity, used_quantity, start_at, end_at, status,
                      created_at, updated_at)
VALUES
    ('WELCOME50', N'Giảm 50.000 ₫ cho đơn đầu tiên', N'Áp dụng cho khách hàng mới, đơn từ 500.000 ₫.',
     'FIXED', 50000, 500000, NULL, 100, 0, DATEADD(DAY, -30, SYSDATETIME()), DATEADD(DAY, 30, SYSDATETIME()), 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    ('BADMINTON10', N'Giảm 10% cho đơn từ 1 triệu', N'Giảm tối đa 200.000 ₫ cho đơn hàng từ 1.000.000 ₫.',
     'PERCENT', 10, 1000000, 200000, 80, 0, DATEADD(DAY, -30, SYSDATETIME()), DATEADD(DAY, 30, SYSDATETIME()), 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    ('FREESHIP30', N'Miễn phí vận chuyển 30.000 ₫', N'Giảm trực tiếp 30.000 ₫ cho đơn từ 300.000 ₫.',
     'FIXED', 30000, 300000, NULL, 150, 0, DATEADD(DAY, -30, SYSDATETIME()), DATEADD(DAY, 30, SYSDATETIME()), 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    ('VIP15', N'Ưu đãi VIP giảm 15%', N'Dành cho đơn hàng từ 2.000.000 ₫, giảm tối đa 300.000 ₫.',
     'PERCENT', 15, 2000000, 300000, 30, 0, DATEADD(DAY, -30, SYSDATETIME()), DATEADD(DAY, 30, SYSDATETIME()), 'ACTIVE', SYSDATETIME(), SYSDATETIME()),
    ('CUOITHANG10', N'Ưu đãi cuối tháng 10%', N'Chương trình đã kết thúc, giữ lại để demo bộ lọc voucher.',
     'PERCENT', 10, 500000, 150000, 50, 12, DATEADD(DAY, -60, SYSDATETIME()), DATEADD(DAY, -5, SYSDATETIME()), 'INACTIVE', SYSDATETIME(), SYSDATETIME());

DECLARE @adminId INT = (SELECT id FROM users WHERE email = 'admin@badstore.local');
IF @adminId IS NULL
    THROW 50003, N'Không tìm thấy tài khoản admin@badstore.local.', 1;

/* ------------------------------------------------------------------------------------------
   5. ĐƠN HÀNG DEMO
   ------------------------------------------------------------------------------------------ */
DECLARE @orders TABLE (
    n INT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    days_ago INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL,
    voucher_code VARCHAR(30) NULL
);

;WITH nums AS (
    SELECT TOP (42) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
    FROM sys.all_objects
)
INSERT INTO @orders (n, email, days_ago, status, source, voucher_code)
SELECT n,
       CHOOSE(((n - 1) % 10) + 1,
              'nguyenminhanh.89@gmail.com',
              'tranquochuy.92@gmail.com',
              'lethuha.95@gmail.com',
              'phamgiahung.90@gmail.com',
              'hoangmaianh.97@gmail.com',
              'vuducmanh.88@gmail.com',
              'dangthuylinh.96@gmail.com',
              'buivankhoa.93@gmail.com',
              'ngothanhha.91@gmail.com',
              'dovanthang.94@gmail.com'),
       n,
       CASE WHEN n IN (1, 2, 9, 20) THEN 'PENDING'
            WHEN n IN (7, 14, 21, 29, 32) THEN 'CONFIRMED'
            WHEN n IN (8, 15, 23, 25, 31, 35, 38) THEN 'SHIPPING'
            WHEN n IN (3, 4, 5, 6, 10, 11, 13, 16, 17, 18, 19, 22, 24, 26, 27, 28, 33, 34) THEN 'DELIVERED'
            WHEN n IN (12, 30, 36, 37, 39, 40) THEN 'CANCELLED'
            ELSE 'RETURNED' END,
       CASE WHEN n IN (5, 14, 23, 32, 39) THEN 'MANUAL' ELSE 'ONLINE' END,
       CASE n WHEN 6 THEN 'WELCOME50'
              WHEN 11 THEN 'BADMINTON10'
              WHEN 18 THEN 'FREESHIP30'
              WHEN 22 THEN 'VIP15'
              WHEN 27 THEN 'WELCOME50'
              WHEN 31 THEN 'BADMINTON10'
              ELSE NULL END
FROM nums;

DECLARE @variantCount INT;
DECLARE @variants TABLE (rn INT PRIMARY KEY, sku VARCHAR(30) NOT NULL);
INSERT INTO @variants (rn, sku)
SELECT ROW_NUMBER() OVER (ORDER BY v.sku), v.sku
FROM product_variants v
JOIN products p ON p.id = v.product_id
JOIN @realCodes c ON c.product_code = p.product_code
WHERE v.status = 'ACTIVE';

SET @variantCount = (SELECT COUNT(*) FROM @variants);
IF @variantCount < 10
    THROW 50004, N'Không đủ biến thể ACTIVE để sinh đơn demo.', 1;

DECLARE @lines TABLE (n INT NOT NULL, sku VARCHAR(30) NOT NULL, quantity INT NOT NULL);
INSERT INTO @lines (n, sku, quantity)
SELECT o.n, v.sku, 1 + (o.n % 2)
FROM @orders o
JOIN @variants v ON v.rn = ((o.n * 7) % @variantCount) + 1;

INSERT INTO @lines (n, sku, quantity)
SELECT o.n, v2.sku, 1
FROM @orders o
JOIN @variants v1 ON v1.rn = ((o.n * 7) % @variantCount) + 1
JOIN @variants v2 ON v2.rn = ((o.n * 13 + 5) % @variantCount) + 1
WHERE o.n % 3 = 0
  AND v2.sku <> v1.sku;

DECLARE @orderMap TABLE (n INT PRIMARY KEY, order_id INT NOT NULL, user_id INT NOT NULL, delivered_at DATETIME2 NULL);

DECLARE @n INT, @email VARCHAR(255), @daysAgo INT, @status VARCHAR(20), @source VARCHAR(20), @voucherCode VARCHAR(30);
DECLARE @userId INT, @addressId INT, @receiverName NVARCHAR(100), @receiverPhone VARCHAR(20), @shippingAddress NVARCHAR(500);
DECLARE @createdAt DATETIME2, @confirmedAt DATETIME2, @shippingAt DATETIME2, @deliveredAt DATETIME2;
DECLARE @cancelledAt DATETIME2, @returnedAt DATETIME2, @updatedAt DATETIME2;
DECLARE @subtotal DECIMAL(18,0), @shipping DECIMAL(18,0), @discount DECIMAL(18,0), @total DECIMAL(18,0);
DECLARE @voucherId INT, @vType VARCHAR(20), @vValue DECIMAL(18,0), @vMin DECIMAL(18,0), @vMax DECIMAL(18,0);
DECLARE @orderId INT, @orderCode VARCHAR(30), @ghnCode VARCHAR(30), @orderNote NVARCHAR(255), @cancelReason NVARCHAR(255);
DECLARE @paymentMethod VARCHAR(20), @paymentStatus VARCHAR(20), @paidAt DATETIME2, @transactionCode VARCHAR(50);
DECLARE @pendingBy INT, @pendingNote NVARCHAR(255), @confirmedNote NVARCHAR(255);
DECLARE @stockDeducted BIT;

DECLARE cur CURSOR LOCAL FAST_FORWARD FOR
    SELECT n, email, days_ago, status, source, voucher_code FROM @orders ORDER BY n;
OPEN cur;
FETCH NEXT FROM cur INTO @n, @email, @daysAgo, @status, @source, @voucherCode;

WHILE @@FETCH_STATUS = 0
BEGIN
    SET @addressId = NULL;
    SET @voucherId = NULL;
    SET @discount = 0;
    SET @deliveredAt = NULL;
    SET @cancelledAt = NULL;
    SET @returnedAt = NULL;

    SELECT @userId = u.id, @receiverName = u.full_name, @receiverPhone = u.phone
    FROM users u
    WHERE u.email = @email;

    IF @userId IS NULL
        THROW 50005, N'Không tìm thấy khách demo để tạo đơn.', 1;

    /* Đơn chờ xác nhận phải mới; đơn đã xác nhận/đang giao cũng nên ở vài ngày gần đây.
       Đơn đã giao/hoàn/hủy giữ ngày gốc để biểu đồ 14 ngày có dữ liệu trải đều. */
    SET @daysAgo = CASE
        WHEN @status = 'PENDING' THEN CASE WHEN @n IN (1, 2) THEN 0 ELSE 1 + (@n % 2) END
        WHEN @status = 'CONFIRMED' THEN 1 + (@n % 5)
        WHEN @status = 'SHIPPING' THEN 3 + (@n % 5)
        ELSE @daysAgo
    END;

    /* Hai đơn giao nhanh nội thành: một đơn hôm nay, một đơn hôm qua.
       Giúp dashboard có doanh thu ngày/tháng ngay khi demo. */
    IF @n = 3 SET @daysAgo = 0;
    IF @n = 4 SET @daysAgo = 1;

    IF @n = 3
        SET @createdAt = DATEADD(HOUR, -5, SYSDATETIME());
    ELSE IF @n = 4
        SET @createdAt = DATEADD(HOUR, -5, DATEADD(DAY, -1, SYSDATETIME()));
    ELSE IF @daysAgo = 0
        SET @createdAt = DATEADD(MINUTE, -30 * @n, SYSDATETIME());
    ELSE
        SET @createdAt = DATEADD(HOUR, -(8 + (@n % 9)),
                                DATEADD(DAY, -@daysAgo, SYSDATETIME()));

    IF @source = 'ONLINE'
    BEGIN
        SELECT @addressId = ua.id,
               @receiverName = ua.receiver_name,
               @receiverPhone = ua.receiver_phone,
               @shippingAddress = CONCAT(ua.address_detail, N', ', ua.ward_name, N', ', ua.district_name, N', ', ua.province_name)
        FROM user_addresses ua
        WHERE ua.user_id = @userId AND ua.is_default = 1 AND ua.is_deleted = 0;

        IF @addressId IS NULL
            THROW 50006, N'Khách demo chưa có địa chỉ mặc định.', 1;
    END
    ELSE
    BEGIN
        SET @addressId = NULL;
        SET @shippingAddress = N'Khách nhận tại cửa hàng BadStore – 123 Nguyễn Trãi, Thanh Xuân, Hà Nội';
    END

    SELECT @subtotal = SUM(v.price * l.quantity)
    FROM @lines l
    JOIN product_variants v ON v.sku = l.sku
    WHERE l.n = @n;

    SET @shipping = CASE
        WHEN @source = 'MANUAL' OR @subtotal >= 5000000 THEN 0
        WHEN @n % 5 = 0 THEN 37000
        WHEN @n % 7 = 0 THEN 42000
        ELSE 30000
    END;

    IF @voucherCode IS NOT NULL AND @status NOT IN ('CANCELLED', 'RETURNED')
    BEGIN
        SELECT @voucherId = v.id, @vType = v.discount_type, @vValue = v.discount_value,
               @vMin = v.min_order_value, @vMax = v.max_discount_amount
        FROM vouchers v
        WHERE v.code = @voucherCode
          AND v.status = 'ACTIVE'
          AND SYSDATETIME() BETWEEN v.start_at AND v.end_at
          AND v.used_quantity < v.quantity;

        IF @voucherId IS NOT NULL AND @subtotal >= @vMin
        BEGIN
            IF @vType = 'PERCENT'
                SET @discount = FLOOR(@subtotal * @vValue / 100.0);
            ELSE
                SET @discount = @vValue;

            IF @vMax IS NOT NULL AND @discount > @vMax
                SET @discount = @vMax;
            IF @discount > @subtotal
                SET @discount = @subtotal;
        END
        ELSE
            SET @voucherId = NULL;
    END

    SET @total = @subtotal + @shipping - @discount;
    SET @stockDeducted = CASE WHEN @status IN ('CONFIRMED', 'SHIPPING', 'DELIVERED') THEN 1 ELSE 0 END;
    SET @orderCode = 'BS' + FORMAT(@createdAt, 'yyMMddHHmmss') + RIGHT('000' + CAST(@n AS VARCHAR(3)), 3);
    SET @ghnCode = CASE
        WHEN @source = 'ONLINE' AND @status IN ('SHIPPING', 'DELIVERED', 'RETURNED')
        THEN 'GHNDEMO' + RIGHT('000000' + CAST(@n AS VARCHAR(6)), 6)
        ELSE NULL
    END;
    SET @orderNote = CASE WHEN @n % 11 = 0 THEN N'Giao ngoài giờ hành chính' ELSE NULL END;
    SET @cancelReason = CASE
        WHEN @status = 'CANCELLED' THEN
            CASE WHEN @n % 2 = 0 THEN N'Khách muốn đổi sang mẫu vợt khác'
                 ELSE N'Thay đổi kế hoạch mua hàng' END
        ELSE NULL
    END;

    INSERT INTO orders (order_code, order_source, user_id, created_by, address_id,
                        receiver_name, receiver_phone, shipping_address,
                        shipping_province_id, shipping_district_id, shipping_ward_code,
                        subtotal, shipping_fee, discount_amount, total_amount, voucher_id,
                        order_status, stock_deducted, note, cancel_reason, ghn_order_code,
                        created_at, updated_at)
    VALUES (@orderCode, @source, @userId,
            CASE WHEN @source = 'MANUAL' THEN @adminId ELSE NULL END,
            @addressId, @receiverName, @receiverPhone, @shippingAddress,
            NULL, NULL, NULL,
            @subtotal, @shipping, @discount, @total, @voucherId,
            @status, @stockDeducted, @orderNote, @cancelReason, @ghnCode,
            @createdAt, @createdAt);

    SET @orderId = SCOPE_IDENTITY();

    INSERT INTO order_items (order_id, product_variant_id, product_name, sku, color,
                             weight_class, grip_size, image_url, unit_price, quantity, subtotal)
    SELECT @orderId, v.id, p.name, v.sku, v.color, v.weight_class, v.grip_size,
           (SELECT TOP (1) pi.image_url
            FROM product_images pi
            WHERE pi.product_id = p.id
            ORDER BY pi.is_primary DESC, pi.sort_order),
           v.price, l.quantity, v.price * l.quantity
    FROM @lines l
    JOIN product_variants v ON v.sku = l.sku
    JOIN products p ON p.id = v.product_id
    WHERE l.n = @n;

    IF @voucherId IS NOT NULL
        UPDATE vouchers
        SET used_quantity = used_quantity + 1,
            updated_at = SYSDATETIME()
        WHERE id = @voucherId;

    SET @confirmedAt = DATEADD(MINUTE, CASE WHEN @source = 'MANUAL' THEN 5 ELSE 25 END, @createdAt);
    SET @shippingAt = DATEADD(DAY, 1, @confirmedAt);
    SET @deliveredAt = DATEADD(DAY, 2, @shippingAt);
    SET @cancelledAt = DATEADD(HOUR, 2, @createdAt);
    SET @returnedAt = DATEADD(DAY, 5, @deliveredAt);

    /* Đơn giao nhanh nội thành: rút ngắn vận chuyển để kịp giao trong ngày/hôm sau. */
    IF @n = 3
    BEGIN
        SET @shippingAt = DATEADD(HOUR, 1, @createdAt);
        SET @deliveredAt = DATEADD(HOUR, 3, @createdAt);
    END
    IF @n = 4
    BEGIN
        SET @shippingAt = DATEADD(HOUR, 1, @createdAt);
        SET @deliveredAt = DATEADD(HOUR, 6, @createdAt);
    END

    SET @pendingBy = CASE WHEN @source = 'MANUAL' THEN @adminId ELSE @userId END;
    SET @pendingNote = CASE WHEN @source = 'MANUAL' THEN N'Admin tạo đơn thủ công' ELSE N'Khách đặt hàng trên website' END;
    SET @confirmedNote = CASE WHEN @source = 'MANUAL' THEN N'Admin xác nhận đơn tại quầy' ELSE N'Admin xác nhận đơn từ danh sách' END;

    INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by, note, created_at)
    VALUES (@orderId, NULL, 'PENDING', @pendingBy, @pendingNote, @createdAt);

    IF @status IN ('CONFIRMED', 'SHIPPING', 'DELIVERED', 'RETURNED')
        INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by, note, created_at)
        VALUES (@orderId, 'PENDING', 'CONFIRMED', @adminId, @confirmedNote, @confirmedAt);

    IF @status IN ('SHIPPING', 'DELIVERED', 'RETURNED')
        INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by, note, created_at)
        VALUES (@orderId, 'CONFIRMED', 'SHIPPING', @adminId, N'Đã bàn giao cho đơn vị vận chuyển', @shippingAt);

    IF @status IN ('DELIVERED', 'RETURNED')
        INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by, note, created_at)
        VALUES (@orderId, 'SHIPPING', 'DELIVERED', @adminId, N'Giao hàng thành công', @deliveredAt);

    IF @status = 'RETURNED'
        INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by, note, created_at)
        VALUES (@orderId, 'DELIVERED', 'RETURNED', @adminId, N'Khách trả hàng: sản phẩm không đúng mô tả', @returnedAt);

    IF @status = 'CANCELLED'
        INSERT INTO order_status_histories (order_id, from_status, to_status, changed_by, note, created_at)
        VALUES (@orderId, 'PENDING', 'CANCELLED',
                CASE WHEN @source = 'ONLINE' THEN @userId ELSE @adminId END,
                CASE WHEN @source = 'ONLINE' THEN N'Khách huỷ đơn: ' + @cancelReason
                     ELSE N'Admin huỷ đơn: ' + @cancelReason END,
                @cancelledAt);

    SET @updatedAt = CASE @status
        WHEN 'PENDING' THEN @createdAt
        WHEN 'CONFIRMED' THEN @confirmedAt
        WHEN 'SHIPPING' THEN @shippingAt
        WHEN 'DELIVERED' THEN @deliveredAt
        WHEN 'CANCELLED' THEN @cancelledAt
        WHEN 'RETURNED' THEN @returnedAt
    END;

    UPDATE orders SET updated_at = @updatedAt WHERE id = @orderId;

    SET @paymentMethod = CASE
        WHEN @source = 'MANUAL' THEN 'CASH'
        WHEN @n % 4 = 0 THEN 'BANK_TRANSFER'
        ELSE 'COD'
    END;

    SET @paymentStatus = CASE
        WHEN @status = 'RETURNED' THEN 'REFUNDED'
        WHEN @status = 'DELIVERED' THEN 'PAID'
        WHEN @status IN ('SHIPPING', 'CONFIRMED') AND @source = 'MANUAL' THEN 'PAID'
        WHEN @status = 'SHIPPING' AND @paymentMethod = 'BANK_TRANSFER' THEN 'PAID'
        WHEN @status = 'CONFIRMED' AND @paymentMethod = 'BANK_TRANSFER' AND @n % 2 = 0 THEN 'PAID'
        ELSE 'PENDING'
    END;

    SET @paidAt = CASE
        WHEN @paymentStatus IN ('PAID', 'REFUNDED') AND @paymentMethod = 'COD' THEN @deliveredAt
        WHEN @paymentStatus IN ('PAID', 'REFUNDED') AND @paymentMethod = 'CASH' THEN @createdAt
        WHEN @paymentStatus IN ('PAID', 'REFUNDED') AND @paymentMethod = 'BANK_TRANSFER' THEN DATEADD(MINUTE, 15, @createdAt)
        ELSE NULL
    END;

    SET @transactionCode = CASE
        WHEN @paymentMethod = 'BANK_TRANSFER' AND @paymentStatus IN ('PAID', 'REFUNDED')
        THEN 'DEMO-BANK-' + RIGHT('000000' + CAST(@n AS VARCHAR(6)), 6)
        ELSE NULL
    END;

    INSERT INTO payments (order_id, payment_method, payment_status, amount, transaction_code,
                          confirmed_by, paid_at, created_at, updated_at)
    VALUES (@orderId, @paymentMethod, @paymentStatus, @total, @transactionCode,
            CASE WHEN @paymentStatus IN ('PAID', 'REFUNDED') THEN @adminId ELSE NULL END,
            @paidAt, @createdAt, @updatedAt);

    INSERT INTO @orderMap (n, order_id, user_id, delivered_at)
    VALUES (@n, @orderId, @userId,
            CASE WHEN @status IN ('DELIVERED', 'RETURNED') THEN @deliveredAt ELSE NULL END);

    FETCH NEXT FROM cur INTO @n, @email, @daysAgo, @status, @source, @voucherCode;
END

CLOSE cur;
DEALLOCATE cur;

/* ------------------------------------------------------------------------------------------
   6. ĐÁNH GIÁ SẢN PHẨM TỪ CÁC ĐƠN ĐÃ GIAO
   ------------------------------------------------------------------------------------------ */
DECLARE @reviewSeed TABLE (n INT PRIMARY KEY, rating TINYINT NOT NULL, comment NVARCHAR(400) NOT NULL, status VARCHAR(20) NOT NULL);
INSERT INTO @reviewSeed (n, rating, comment, status) VALUES
    (6,  5, N'Sản phẩm ổn trong tầm giá, hộp hàng cẩn thận.', 'VISIBLE'),
    (10, 4, N'Giao hàng hơi chậm một chút nhưng sản phẩm rất tốt.', 'VISIBLE'),
    (11, 5, N'Chất lượng đúng như mô tả, nhân viên tư vấn nhiệt tình.', 'VISIBLE'),
    (13, 4, N'Vợt nhẹ, vung nhanh, phù hợp người mới chơi.', 'VISIBLE'),
    (16, 5, N'Màu ngoài đời đẹp hơn ảnh. Đóng gói cẩn thận.', 'VISIBLE'),
    (17, 5, N'Cây vợt tấn công chắc tay, đập cầu uy lực. Giao hàng nhanh, đóng gói kỹ.', 'VISIBLE'),
    (18, 5, N'Mua tặng chồng, anh ấy rất thích. Vợt đánh đôi chắc tay, đập cầu tốt.', 'VISIBLE'),
    (19, 3, N'Sản phẩm tạm ổn nhưng giao hàng hơi chậm.', 'HIDDEN'),
    (22, 5, N'Vợt đẹp, cầm chắc tay. Giao sớm hơn dự kiến.', 'VISIBLE'),
    (24, 5, N'Cây vợt tấn công rất chắc tay, đáng tiền.', 'VISIBLE'),
    (26, 4, N'Vợt nhẹ, linh hoạt, phù hợp phong trào.', 'VISIBLE'),
    (27, 5, N'Đã mua lần 2, vẫn rất hài lòng về chất lượng.', 'VISIBLE'),
    (28, 5, N'Màu ngoài đời đẹp, đóng gói cẩn thận.', 'VISIBLE'),
    (33, 4, N'Shop hỗ trợ đổi size cán vợt nhanh, cảm ơn shop.', 'VISIBLE'),
    (34, 5, N'Hàng chính hãng, giá hợp lý, sẽ giới thiệu bạn bè.', 'VISIBLE');

INSERT INTO product_reviews (product_id, user_id, order_item_id, rating, comment, status, created_at, updated_at)
SELECT v.product_id, m.user_id, oi.id, r.rating, r.comment, r.status,
       DATEADD(DAY, 2 + (r.n % 3), m.delivered_at),
       DATEADD(DAY, 2 + (r.n % 3), m.delivered_at)
FROM @reviewSeed r
JOIN @orderMap m ON m.n = r.n
JOIN order_items oi ON oi.order_id = m.order_id
JOIN product_variants v ON v.id = oi.product_variant_id
WHERE oi.id = (SELECT MIN(oi2.id) FROM order_items oi2 WHERE oi2.order_id = m.order_id)
  AND m.delivered_at IS NOT NULL;

/* ------------------------------------------------------------------------------------------
   7. GIỎ HÀNG MẪU CHO KHÁCH NGUYỄN MINH ANH
   ------------------------------------------------------------------------------------------ */
INSERT INTO cart_items (user_id, product_variant_id, quantity, created_at, updated_at)
SELECT u.id, v.id,
       CASE WHEN v.sku = 'AP-FW55-02' THEN 2 ELSE 1 END,
       SYSDATETIME(), SYSDATETIME()
FROM users u
JOIN product_variants v ON v.sku IN ('YX-AX100ZZ-01', 'AP-FW55-02')
WHERE u.email = 'nguyenminhanh.89@gmail.com';

COMMIT;

/* ------------------------------------------------------------------------------------------
   8. TÓM TẮT
   ------------------------------------------------------------------------------------------ */
PRINT N'Đã nạp bộ dữ liệu demo BadStore.';
SELECT (SELECT COUNT(*) FROM @demoUsers) AS demo_customers,
       (SELECT COUNT(*) FROM @orderMap) AS demo_orders,
       (SELECT COUNT(*) FROM @reviewSeed) AS demo_reviews,
       (SELECT COUNT(*) FROM cart_items) AS demo_cart_items,
       (SELECT COUNT(*) FROM product_variants v JOIN products p ON p.id = v.product_id WHERE p.is_deleted = 0) AS real_variants;

END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0
        ROLLBACK;
    THROW;
END CATCH;
END
GO

/* ============================ PHỤ LỤC. GHI CHÚ KHI CODE ============================ */
/*
 A. THAY ĐỔI TRẠNG THÁI ĐƠN (mỗi lần = 1 transaction; luôn ghi order_status_histories)
    Luồng hợp lệ - kiểm tra ở Service, DB chặn trạng thái/stock sai bằng CK_orders_stock:
      PENDING   -> CONFIRMED | CANCELLED
      CONFIRMED -> SHIPPING  | CANCELLED
      SHIPPING  -> DELIVERED | CANCELLED
      DELIVERED -> RETURNED   (hoàn trả 100% đơn)

 B. XÁC NHẬN ĐƠN (PENDING -> CONFIRMED) = TRỪ KHO. Duyệt từng order_item, sắp xếp theo
    product_variant_id tăng dần (tránh deadlock):
      UPDATE product_variants SET stock_quantity = stock_quantity - @qty
      WHERE id = @variantId AND stock_quantity >= @qty;      -- rows affected = 0 => rollback,
                                                             -- báo "Không đủ tồn kho cho SKU ..."
    Sau đó, trong CÙNG câu UPDATE: order_status = 'CONFIRMED', stock_deducted = 1.

 C. HỦY / HOÀN TRẢ:
    - Hủy khi PENDING: không đụng kho (chưa trừ). stock_deducted vẫn = 0.
    - Hủy khi CONFIRMED/SHIPPING, hoặc RETURNED từ DELIVERED: CỘNG lại kho
        UPDATE product_variants SET stock_quantity = stock_quantity + @qty WHERE id = @variantId;
      rồi UPDATE orders SET order_status = ..., stock_deducted = 0 (cùng 1 câu UPDATE).
    - Mọi trường hợp hủy: trả lượt voucher (vouchers.used_quantity - 1). Nếu RETURNED: payment REFUNDED nếu đã PAID.

 D. ĐẶT HÀNG (ONLINE) - chưa trừ kho nhưng PHẢI validate số lượng:
    kiểm tra stock_quantity >= quantity, variant.status = 'ACTIVE', sản phẩm status = 'ACTIVE'.
    Tạo orders + order_items (snapshot) + payments + dùng voucher + xóa cart_items của user
    trong 1 transaction.

 E. TẠO ĐƠN THỦ CÔNG (admin): order_source = 'MANUAL', created_by = id admin,
    user_id có thể NULL (khách vãng lai), shipping_address có thể NULL (mua tại quầy),
    payment_method = 'CASH' nếu thu tiền ngay. Có thể tạo PENDING rồi confirm luôn trong cùng transaction.

 F. VOUCHER (áp cho tổng hóa đơn, tính trên subtotal):
      UPDATE vouchers SET used_quantity = used_quantity + 1
      WHERE id = @id AND used_quantity < quantity AND status = 'ACTIVE'
        AND SYSDATETIME() BETWEEN start_at AND end_at;      -- rows affected = 1
    Kiểm tra min_order_value, giới hạn max_discount_amount ở Service.
    Muốn "mỗi khách dùng 1 lần":
      SELECT COUNT(*) FROM orders
      WHERE user_id = @u AND voucher_id = @v AND order_status NOT IN ('CANCELLED');

 G. THANH TOÁN: COD -> payment_status = 'PAID' (+ paid_at) khi đơn DELIVERED.
    Chuyển khoản -> khách chuyển với nội dung = order_code, admin kiểm tra rồi bấm xác nhận
    (confirmed_by = admin, paid_at = now). Nếu sau này làm webhook ngân hàng thì thêm lại cột
    expires_at, gateway_payload (xem BadStore_v2.sql).

 H. GHN: phí ship lấy từ fee API lúc checkout -> orders.shipping_fee.
    Khối lượng gói hàng (gram) để hằng số trong cấu hình, KHÔNG dùng cột weight_class '3U/4U'.
    Token GHN để trong application.yml, không commit lên Git.

 I. UI: mọi thao tác ghi CSDL (thêm/sửa/xóa sản phẩm, đổi trạng thái đơn, hủy đơn...) cần có
    hộp thoại xác nhận trước + thông báo thành công/thất bại sau (đúng tiêu chí đề tài).

 J. TÌM KIẾM: LIKE N'%từ khóa%' trên products.name (+ lọc brand_id, category_id, khoảng giá theo
    MIN(price) từ product_variants). Lọc hiển thị: is_deleted = 0 AND status = 'ACTIVE'.

 K. Hibernate: ddl-auto=none (hoặc validate); @Nationalized cho cột NVARCHAR;
    @Enumerated(EnumType.STRING) cho các cột status/method/role/balance_type/shaft_flexibility.
*/

