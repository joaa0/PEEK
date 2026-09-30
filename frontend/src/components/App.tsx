"use client";
import { useEffect, useState, type MouseEvent } from "react";
import {
  Boxes,
  FlaskConical,
  LayoutDashboard,
  PackageSearch,
  ReceiptText,
  TriangleAlert,
} from "lucide-react";
import Alerts from "./Alerts";
import Fiscal from "./Fiscal";
import Investigation from "./Investigation";
import Products, { ProductDetail } from "./Products";
import { Card } from "./operational-ui";

const navigation = [
  { path: "/overview", label: "Visão geral", icon: LayoutDashboard },
  { path: "/products", label: "Produtos", icon: Boxes },
  { path: "/inventory", label: "Estoque", icon: PackageSearch },
  { path: "/fiscal", label: "Fiscal", icon: ReceiptText },
  { path: "/exceptions", label: "Central de exceções", icon: TriangleAlert },
  { path: "/simulation", label: "Simulação", icon: FlaskConical },
] as const;

function PendingScreen({ label }: { label: string }) {
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Operação conectada</div>
          <h1>{label}</h1>
        </div>
      </header>
      <Card title="Em preparação">
        <p>
          Esta área terá uma tela própria em uma próxima etapa. Use Produtos e
          Central de exceções para consultar o estado operacional disponível.
        </p>
      </Card>
    </>
  );
}

export default function App() {
  const [path, setPath] = useState<string | null>(null);
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
  function follow(event: MouseEvent<HTMLAnchorElement>, next: string) {
    if (
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    )
      return;
    event.preventDefault();
    navigate(next);
  }
  const segments = path?.split("/").filter(Boolean) ?? [];
  const route =
    path === null ? null : segments[0] ? "/" + segments[0] : "/overview";
  const current = navigation.find((item) => item.path === route);
  const detail =
    route === "/exceptions" && segments[1]
      ? "Investigação"
      : route === "/products" && segments[1]
        ? "Detalhe do produto"
        : null;
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="sidebar-top">
          <a
            className="brand"
            href="/overview"
            onClick={(event) => follow(event, "/overview")}
            aria-label="PEEK · Visão geral"
          >
            <img src="/peek-logo.png" alt="" width={34} height={44} />
            <span>PEEK</span>
          </a>
        </div>
        <div className="nav-label">OPERAÇÃO CONECTADA</div>
        <nav aria-label="Navegação principal">
          {navigation.map(({ path: destination, label, icon: Icon }) => (
            <a
              key={destination}
              href={destination}
              className={"nav-item " + (route === destination ? "active" : "")}
              aria-current={route === destination ? "page" : undefined}
              onClick={(event) => follow(event, destination)}
            >
              <Icon size={18} aria-hidden="true" />
              {label}
            </a>
          ))}
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
            PEEK /{" "}
            <strong>
              {current?.label ??
                (path === null ? "Carregando" : "Página não encontrada")}
            </strong>
            {detail && (
              <>
                {" "}
                / <span>{detail}</span>
              </>
            )}
          </div>
          <span className="environment">MVP · FONTES SIMULADAS</span>
        </header>
        <main>
          {path === null ? (
            <p role="status" className="state-message">
              Carregando navegação…
            </p>
          ) : route === "/exceptions" && segments[1] ? (
            <Investigation
              key={segments[1]}
              id={segments[1]}
              navigate={navigate}
            />
          ) : route === "/products" && segments[1] ? (
            <ProductDetail
              key={segments[1]}
              id={segments[1]}
              navigate={navigate}
            />
          ) : route === "/products" ? (
            <Products navigate={navigate} />
          ) : route === "/exceptions" ? (
            <Alerts navigate={navigate} />
          ) : route === "/fiscal" ? (
            <Fiscal navigate={navigate} />
          ) : current ? (
            <PendingScreen label={current.label} />
          ) : (
            <Card title="Página não encontrada">
              <a
                href="/overview"
                onClick={(event) => follow(event, "/overview")}
              >
                Ir para a visão geral
              </a>
            </Card>
          )}
        </main>
      </div>
    </div>
  );
}
