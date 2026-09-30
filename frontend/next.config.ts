import type { NextConfig } from "next";
const nextConfig: NextConfig = {
  reactStrictMode: true,
  agentRules: false,
  async rewrites() {
    const backend = (
      process.env.PEEK_API_URL || "http://127.0.0.1:8080"
    ).replace(/\/$/, "");
    return [
      { source: "/api/v1/:path*", destination: backend + "/api/v1/:path*" },
    ];
  },
};
export default nextConfig;
