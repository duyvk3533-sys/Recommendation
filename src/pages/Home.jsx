import products from "../data/products";
import ProductCard from "../components/ProductCard";
import BannerCarousel from "../components/BannerCarousel";

const VIEW_HISTORY_KEY = "beauty_shop_view_history";

function getViewHistory() {
  try {
    return JSON.parse(localStorage.getItem(VIEW_HISTORY_KEY) || "[]");
  } catch {
    return [];
  }
}

function getRecommendedProducts() {
  const history = getViewHistory();
  const viewedSet = new Set(history);

  return [...products]
    .map((product, index) => {
      let score = 0;

      if (viewedSet.has(product.id)) score += 6;
      if (product.isHot) score += 3;
      if (product.isSale) score += 2;
      score += (products.length - index) * 0.01;

      return { product, score };
    })
    .sort((a, b) => b.score - a.score)
    .map((item) => item.product)
    .slice(0, 4);
}

export default function Home() {
  const recommendedProducts = getRecommendedProducts();

  return (
    <div>
      <BannerCarousel />

      <h2>Dành cho bạn</h2>
      <p>Gợi ý dựa trên sản phẩm bạn đã xem gần đây trong trình duyệt.</p>
      <div className="grid">
        {recommendedProducts.map((sp) => (
          <ProductCard key={sp.id} product={sp} />
        ))}
      </div>

      <h2>Sản phẩm nổi bật</h2>
      <div className="grid">
        {products.slice(0, 4).map((sp) => (
          <ProductCard key={sp.id} product={sp} />
        ))}
      </div>

      <h2>Tất cả sản phẩm</h2>
      <div className="grid">
        {products.map((sp) => (
          <ProductCard key={sp.id} product={sp} />
        ))}
      </div>
    </div>
  );
}
