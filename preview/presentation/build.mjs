// node build.mjs  -> sahirep-presentation.html (self-contained)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const S = (p) => path.join(root, 'data', 'screens', p);
const imgs = {
  home: [S('frontend/c3_01_home_top.png'), 'Home screen'],
  log: [S('frontend/c3_14_workout_log.png'), 'Workout log screen'],
  progress: [S('frontend/c3_16_progress_top.png'), 'Progress screen'],
  demos: [S('main/demos_01_first.png'), 'Exercise demo screen'],
  dl: [S('frontend/c5_07_dl_compare45.png'), 'Deadlift 3D viewer with target ghost'],
  push: [S('frontend/c5_12_pushups_viewer.png'), 'Push-up 3D viewer'],
};
let html = fs.readFileSync(path.join(here, 'template.html'), 'utf8');
html = html.replace(/\{\{IMG:(\w+)(?:\|(\w+))?\}\}/g, (_, k, cls) => {
  const [f, alt] = imgs[k];
  const b64 = fs.readFileSync(f).toString('base64');
  return `<img${cls ? ` class="${cls}"` : ''} alt="${alt}" src="data:image/png;base64,${b64}">`;
});
const demo = fs.readFileSync(path.join(root, 'preview', 'demo', 'sahirep-demo.html'), 'utf8')
  .replace(/&/g, '&amp;').replace(/"/g, '&quot;');
html = html.replace('{{DEMO}}', () => demo);
const out = path.join(here, 'sahirep-presentation.html');
fs.writeFileSync(out, html);
console.log(out, (fs.statSync(out).size / 1048576).toFixed(2) + ' MB');
