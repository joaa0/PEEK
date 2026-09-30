"use client";
import { useEffect, useState } from "react";
import { Boxes, TriangleAlert } from "lucide-react";
import Alerts from "./Alerts";
import Investigation from "./Investigation";
import Products, { ProductDetail } from "./Products";

export default function App() {
  const [path, setPath] = useState("/exceptions");
  useEffect(() => {
    const update = () => setPath(window.location.pathname);
    update();
    window.addEventListener("popstate", update);
    return () => window.removeEventListener("popstate", update);
  }, []);
  function navigate(next: string) {
    window.history.pushState(null, "", next);
    setPath(next);
    window.scrollTo(0, 0);
  }
  const segments = path.split("/").filter(Boolean);
  const products = segments[0] === "products";
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="sidebar-top">
          <a className="brand" href="/exceptions">
            <span>
              PEEK<span className="brand-dot">io</span>
            </span>
          </a>
        </div>
        <div className="nav-label">OPERAÇÃO CONECTADA</div>
        <nav aria-label="Navegação principal">
          <button
            className={"nav-item " + (!products ? "active" : "")}
            onClick={() => navigate("/exceptions")}
          >
            <TriangleAlert size={18} />
            Central de exceções
          </button>
          <button
            className={"nav-item " + (products ? "active" : "")}
            onClick={() => navigate("/products")}
          >
            <Boxes size={18} />
            Produtos e mappings
          </button>
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-note">
            Evidências antes da ação
            <small>
              Integrações externas simuladas no backend. Eventos e tentativas
              preservados.
            </small>
          </div>
        </div>
      </aside>
      <div className="main-column">
        <header className="topbar">
          <div className="breadcrumbs">
            PEEKio / <strong>{products ? "Produtos" : "Exceções"}</strong>
          </div>
          <span className="environment">MVP · FONTES SIMULADAS</span>
        </header>
        <main>
          {segments[0] === "exceptions" && segments[1] ? (
            <Investigation
              key={segments[1]}
              id={segments[1]}
              navigate={navigate}
            />
          ) : products && segments[1] ? (
            <ProductDetail
              key={segments[1]}
              id={segments[1]}
              navigate={navigate}
            />
          ) : products ? (
            <Products navigate={navigate} />
          ) : (
            <Alerts navigate={navigate} />
          )}
        </main>
      </div>
    </div>
  );
}
