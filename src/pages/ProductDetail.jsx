import { useParams } from "react-router-dom";
import products from "../data/products";
import { useEffect, useState } from "react";

const VIEW_HISTORY_KEY = "beauty_shop_view_history";

function saveViewedProduct(productId) {
  try {
    const existing = JSON.parse(localStorage.getItem(VIEW_HISTORY_KEY) || "[]");
    const next = [productId, ...existing.filter((id) => id !== productId)].slice(0, 12);
    localStorage.setItem(VIEW_HISTORY_KEY, JSON.stringify(next));
  } catch {
    // Ignore storage errors in demo mode.
  }
}

export default function ProductDetail() {
  const { id } = useParams();
  const sp = products.find((p) => p.id == id);

  const [soLuong, setSoLuong] = useState(1);

  useEffect(() => {
    if (sp) {
      saveViewedProduct(sp.id);
    }
  }, [sp]);

  if (!sp) return <p>Không tìm thấy sản phẩm</p>;

  return (
    <div>
      <img src={sp.image} width="300" />

      <h2>{sp.name}</h2>

      <p>Giá: {sp.salePrice.toLocaleString("vi-VN")} đ</p>

      <div>
        <button onClick={() => setSoLuong(Math.max(1, soLuong - 1))}>
          -
        </button>
        <span> {soLuong} </span>
        <button onClick={() => setSoLuong(soLuong + 1)}>
          +
        </button>
      </div>

      <button>Thêm vào giỏ hàng</button>

      <h3>Đánh giá</h3>
      <p>⭐⭐⭐⭐☆</p>

      <h4>Bình luận</h4>
      <p>Chưa có bình luận</p>
    </div>
  );
}
