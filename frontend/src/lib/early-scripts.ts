/**
 * Tiny scripts that run before the page is painted, so a returning visitor never sees the wrong colours flash.
 * They live in a plain module (not a client component) because the server layout writes them into the page.
 */
export const MODE_STORAGE_KEY = "rm_mode";
export const THEME_STORAGE_KEY = "rm_theme";

/** The saved light/dark choice, or else the device's own setting. */
const MODE_SCRIPT = `try{var m=localStorage.getItem("${MODE_STORAGE_KEY}");if(m!=="dark"&&m!=="light"){m=window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light"}document.documentElement.dataset.mode=m}catch(e){}`;

/** The restaurant's look as it was last time on this device. */
const THEME_SCRIPT = `try{var t=localStorage.getItem("${THEME_STORAGE_KEY}");if(t==="duodecim")document.documentElement.dataset.theme=t}catch(e){}`;

export const EARLY_SCRIPT = MODE_SCRIPT + THEME_SCRIPT;
