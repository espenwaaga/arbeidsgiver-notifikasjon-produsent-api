import typescript from '@rollup/plugin-typescript';
import postcss from "rollup-plugin-postcss";
import resolve from "@rollup/plugin-node-resolve";
import commonjs from "@rollup/plugin-commonjs"
import url from "@rollup/plugin-url";
import { readFileSync } from 'fs'

const packageJson = JSON.parse(String(readFileSync("./package.json")));

export default {
  input: 'src/index.tsx',
  output: [
    {
      file: packageJson.main,
      format: "cjs",
      sourcemap: true,
    },
    {
      file: packageJson.module,
      format: "esm",
      sourcemap: true,
    }
  ],
  external: [
    ...Object.keys(packageJson.dependencies),
    ...Object.keys(packageJson.peerDependencies)
  ],
  plugins: [
    typescript({ tsconfig: './tsconfig.json' }),
    commonjs(),
    resolve(),
    postcss({
      extract: true
    }),
    url({
      include: ["**/*.ttf"],
      limit: Infinity,
    }),
  ]
};
