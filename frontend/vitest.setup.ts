import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";
beforeEach(() => {
  window.scrollTo = vi.fn();
  localStorage.clear();
  window.history.replaceState(null, "", "/dashboard");
});
afterEach(() => cleanup());
