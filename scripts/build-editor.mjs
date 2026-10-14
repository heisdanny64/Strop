import * as esbuild from "esbuild";
import { mkdirSync } from "fs";

mkdirSync("assets/web/editor", { recursive: true });

await esbuild.build({
  entryPoints: ["assets/web/editor/editor.ts"],
  bundle: true,
  format: "iife",
  outfile: "assets/web/editor/editor.js",
  target: "es2020",
  minify: process.argv.includes("--minify"),
  logLevel: "info",
});
