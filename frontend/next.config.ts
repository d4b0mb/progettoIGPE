import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

// Where the Spring Boot API lives. The browser only ever talks to this app's own
// origin; "/api/*" is forwarded to the backend. That keeps the login refresh
// cookie first-party (SameSite=Strict) and avoids cross-origin (CORS) rules.
// In production a reverse proxy does the same job in front of both services.
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // Self-contained server output: a small Docker image with no node_modules copy.
  output: "standalone",
  // Compression would buffer the live kitchen stream (server-sent events).
  // The reverse proxy in front compresses instead.
  compress: false,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default createNextIntlPlugin("./src/i18n/request.ts")(nextConfig);
