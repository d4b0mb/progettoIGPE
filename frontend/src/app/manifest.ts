import type { MetadataRoute } from "next";

/** Lets a phone or tablet install the app to the home screen and open it full screen, without browser bars. */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "RistoManager",
    short_name: "RistoManager",
    description: "Reservations, floor plan and live kitchen orders for restaurants.",
    start_url: "/",
    display: "standalone",
    background_color: "#0e2b1a",
    theme_color: "#0e2b1a",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
    ],
  };
}
