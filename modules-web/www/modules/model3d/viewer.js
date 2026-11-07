// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// 3D model viewer: GLB/glTF, STL, OBJ (+MTL), PLY, 3MF, FBX, DAE, 3DS, AMF, G-code, PCD, XYZ, VOX.
// One finger turns the model, two fingers zoom and move it. Everything runs offline (three.js is bundled).

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { host } from '../host/xander.js';

const $ = (s) => document.querySelector(s);
const canvas = $('#view'), msg = $('#msg');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(45, 1, 0.01, 1000);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.autoRotateSpeed = 2.5;
const studio = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
const sun = new THREE.DirectionalLight(0xffffff, 2.2); sun.position.set(3, 5, 4);
const fill = new THREE.HemisphereLight(0xffffff, 0x444455, 1.2);
scene.add(sun, fill);
const grid = new THREE.GridHelper(2, 20, 0x555a66, 0x2c3038);
scene.add(grid);

const BACKDROPS = [0x101114, 0x2a2d34, 0xe9ebef];
let backdrop = 0, lightMode = 0, model = null, mixer = null, home = null;
const clock = new THREE.Clock();

function resize() {
  const r = canvas.parentElement.getBoundingClientRect();
  renderer.setSize(r.width, r.height, false);
  camera.aspect = r.width / Math.max(1, r.height);
  camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(canvas.parentElement);

function applyLook() {
  scene.background = new THREE.Color(BACKDROPS[backdrop]);
  scene.environment = lightMode === 0 ? studio : null;
  sun.intensity = lightMode === 2 ? 0 : 2.2; fill.intensity = lightMode === 2 ? 3 : 1.2;
  $('#light-l').textContent = ['Studio', 'Sun', 'Flat'][lightMode];
}

// Fits the camera to the model and rests it on the grid.
function frame(obj) {
  const box = new THREE.Box3().setFromObject(obj);
  if (box.isEmpty()) return;
  const size = box.getSize(new THREE.Vector3()), center = box.getCenter(new THREE.Vector3());
  const r = Math.max(size.x, size.y, size.z) || 1;
  obj.position.sub(new THREE.Vector3(center.x, box.min.y, center.z));
  grid.scale.setScalar(r * 1.5);
  // Fit the narrower field of view (the horizontal one on a phone held upright).
  const half = Math.min(Math.tan((camera.fov * Math.PI) / 360), Math.tan((camera.fov * Math.PI) / 360) * camera.aspect);
  const d = (r / (2 * half)) * 1.25;
  camera.near = r / 500; camera.far = r * 100; camera.updateProjectionMatrix();
  home = { pos: new THREE.Vector3(d * 0.6, size.y * 0.5 + d * 0.35, d * 0.8), target: new THREE.Vector3(0, size.y / 2, 0) };
  resetView();
  const tris = countTriangles(obj);
  const fmt = (v) => (v >= 100 ? v.toFixed(0) : v >= 1 ? v.toFixed(2) : v.toPrecision(3));
  $('#info').textContent = `${fmt(size.x)} × ${fmt(size.y)} × ${fmt(size.z)} units · ${tris.toLocaleString()} triangles`;
}
function resetView() { if (!home) return; camera.position.copy(home.pos); controls.target.copy(home.target); controls.update(); }
function countTriangles(obj) {
  let n = 0;
  obj.traverse((o) => { const g = o.geometry; if (o.isMesh && g) n += (g.index ? g.index.count : g.attributes.position.count) / 3; });
  return Math.round(n);
}

// Single-file formats come as a geometry (STL, PLY) or an object; give bare geometry a neutral material.
const solid = (geo) => {
  // Many STL exporters write zero normals: recompute them, or the model draws black.
  const n = geo.attributes.normal;
  if (!n || !n.array.some((x) => x !== 0)) geo.computeVertexNormals();
  const mat = new THREE.MeshStandardMaterial({ color: geo.attributes.color ? 0xffffff : 0xb8c0cc, metalness: 0.1, roughness: 0.55, vertexColors: !!geo.attributes.color });
  return new THREE.Mesh(geo, mat);
};
const points = (geo) => new THREE.Points(geo, new THREE.PointsMaterial({ size: 0.01, vertexColors: !!geo.attributes.color, sizeAttenuation: true }));

const dirOf = (p) => p.slice(0, p.lastIndexOf('/') + 1);
// Files next to the model (a .gltf's .bin and textures, an .obj's .mtl): resolved through the file manager.
async function siblingUrls(path, names) {
  const map = new Map();
  if (!path) return map;
  for (const n of names) {
    if (!n || /^(data:|blob:|https?:)/.test(n)) continue;
    const f = await host.openPath(dirOf(path) + decodeURIComponent(n)).catch(() => null);
    if (f?.url) map.set(n, f.url);
  }
  return map;
}
function managerFor(map) {
  const m = new THREE.LoadingManager();
  m.setURLModifier((u) => { for (const [k, v] of map) if (u === k || u.endsWith('/' + k) || decodeURIComponent(u).endsWith(k)) return v; return u; });
  return m;
}

async function load(file) {
  const ext = file.name.split('.').pop().toLowerCase();
  const buf = await (await fetch(file.url)).arrayBuffer();
  const text = () => new TextDecoder().decode(buf);
  switch (ext) {
    case 'glb': case 'gltf': case 'vrm': {
      const { GLTFLoader } = await import('three/addons/loaders/GLTFLoader.js');
      const { DRACOLoader } = await import('three/addons/loaders/DRACOLoader.js');
      const { MeshoptDecoder } = await import('three/addons/libs/meshopt_decoder.module.js');
      let map = new Map();
      if (ext === 'gltf') { const j = JSON.parse(text()); map = await siblingUrls(file.path, [...(j.buffers || []), ...(j.images || [])].map((x) => x.uri)); }
      const l = new GLTFLoader(managerFor(map)).setDRACOLoader(new DRACOLoader().setDecoderPath('./vendor/three/addons/libs/draco-gltf/')).setMeshoptDecoder(MeshoptDecoder);
      const g = await l.parseAsync(buf, './');
      if (g.animations?.length) { mixer = new THREE.AnimationMixer(g.scene); g.animations.forEach((a) => mixer.clipAction(a).play()); $('#anim').hidden = false; $('#anim').setAttribute('aria-pressed', 'true'); }
      return g.scene;
    }
    case 'stl': { const { STLLoader } = await import('three/addons/loaders/STLLoader.js'); return solid(new STLLoader().parse(buf)); }
    case 'ply': { const { PLYLoader } = await import('three/addons/loaders/PLYLoader.js'); const g = new PLYLoader().parse(buf); return g.index || g.attributes.normal ? solid(g) : points(g); }
    case 'obj': {
      const { OBJLoader } = await import('three/addons/loaders/OBJLoader.js');
      const src = text(), l = new OBJLoader();
      const mtl = [...src.matchAll(/^mtllib\s+(.+)$/gm)].map((m) => m[1].trim());
      if (mtl.length) {
        const { MTLLoader } = await import('three/addons/loaders/MTLLoader.js');
        const urls = await siblingUrls(file.path, mtl);
        const mtlUrl = urls.get(mtl[0]);
        if (mtlUrl) {
          const mtlText = await (await fetch(mtlUrl)).text();
          const tex = [...mtlText.matchAll(/^\s*map_\w+\s+(?:-\S+\s+\S+\s+)*(.+)$/gm)].map((m) => m[1].trim());
          const mats = new MTLLoader(managerFor(await siblingUrls(file.path, tex))).parse(mtlText, '');
          mats.preload(); l.setMaterials(mats);
        }
      }
      return l.parse(src);
    }
    case '3mf': { const { ThreeMFLoader } = await import('three/addons/loaders/3MFLoader.js'); const o = new ThreeMFLoader().parse(buf); o.rotation.x = -Math.PI / 2; return o; }
    case 'fbx': {
      const { FBXLoader } = await import('three/addons/loaders/FBXLoader.js');
      const o = new FBXLoader().parse(buf, '');
      if (o.animations?.length) { mixer = new THREE.AnimationMixer(o); o.animations.forEach((a) => mixer.clipAction(a).play()); $('#anim').hidden = false; $('#anim').setAttribute('aria-pressed', 'true'); }
      return o;
    }
    case 'dae': { const { ColladaLoader } = await import('three/addons/loaders/ColladaLoader.js'); return new ColladaLoader().parse(text(), '').scene; }
    case '3ds': { const { TDSLoader } = await import('three/addons/loaders/TDSLoader.js'); return new TDSLoader().parse(buf, ''); }
    case 'amf': { const { AMFLoader } = await import('three/addons/loaders/AMFLoader.js'); return new AMFLoader().parse(buf); }
    case 'gcode': case 'gco': { const { GCodeLoader } = await import('three/addons/loaders/GCodeLoader.js'); const o = new GCodeLoader().parse(text()); o.rotation.x = -Math.PI / 2; return o; }
    case 'pcd': { const { PCDLoader } = await import('three/addons/loaders/PCDLoader.js'); return new PCDLoader().parse(buf); }
    case 'xyz': return points(parseXYZ(text()));
    case 'vox': {
      const { VOXLoader, VOXMesh } = await import('three/addons/loaders/VOXLoader.js');
      const g = new THREE.Group(); for (const chunk of new VOXLoader().parse(buf)) g.add(new VOXMesh(chunk)); return g;
    }
    default: throw new Error(`.${ext} files aren't supported`);
  }
}
function parseXYZ(src) {
  const v = [], c = [];
  for (const line of src.split('\n')) {
    const p = line.trim().split(/[\s,]+/).map(Number);
    if (p.length >= 3 && p.slice(0, 3).every(Number.isFinite)) { v.push(p[0], p[1], p[2]); if (p.length >= 6) c.push(...p.slice(3, 6).map((x) => (x > 1 ? x / 255 : x))); }
  }
  const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(v, 3));
  if (c.length === v.length) g.setAttribute('color', new THREE.Float32BufferAttribute(c, 3));
  return g;
}

async function open(file) {
  if (!file) { msg.textContent = 'Open a 3D model (GLB, glTF, STL, OBJ, PLY, 3MF, FBX…)'; return; }
  $('#name').textContent = file.name; msg.textContent = 'Opening…'; msg.className = 'msg';
  if (model) { scene.remove(model); model = null; mixer = null; $('#anim').hidden = true; }
  try {
    model = await load(file);
    model.traverse((o) => { if (o.isPoints) o.material.size = 0; });
    scene.add(model);
    frame(model);
    model.traverse((o) => { if (o.isPoints) { const r = new THREE.Box3().setFromObject(model).getSize(new THREE.Vector3()).length(); o.material.size = r / 400; } });
    msg.textContent = '';
  } catch (e) {
    msg.textContent = `Couldn't open this model: ${e.message || e}`; msg.className = 'msg err';
    console.error(e);
  }
}

$('#reset').onclick = resetView;
$('#spin').onclick = (e) => { controls.autoRotate = !controls.autoRotate; e.currentTarget.setAttribute('aria-pressed', String(controls.autoRotate)); };
$('#wire').onclick = (e) => {
  const on = e.currentTarget.getAttribute('aria-pressed') !== 'true';
  e.currentTarget.setAttribute('aria-pressed', String(on));
  model?.traverse((o) => { if (o.isMesh) (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { m.wireframe = on; }); });
};
$('#light').onclick = () => { lightMode = (lightMode + 1) % 3; applyLook(); };
$('#bg').onclick = () => { backdrop = (backdrop + 1) % BACKDROPS.length; applyLook(); };
$('#anim').onclick = (e) => { const on = e.currentTarget.getAttribute('aria-pressed') !== 'true'; e.currentTarget.setAttribute('aria-pressed', String(on)); };
$('#back').onclick = () => host.exit();
$('#open').onclick = async () => {
  const [f] = await host.pick({ accept: ['model/*', 'application/octet-stream', '*/*'] });
  if (f) open(f);
};

applyLook();
resize();
renderer.setAnimationLoop(() => {
  const dt = clock.getDelta();
  if (mixer && $('#anim').getAttribute('aria-pressed') === 'true') mixer.update(dt);
  controls.update();
  renderer.render(scene, camera);
});
window.__viewer = { scene, countTriangles: () => (model ? countTriangles(model) : 0) };
// In a desktop browser (tests): ?file=<url> opens that file.
const q = new URLSearchParams(location.search).get('file');
open(host.launch || (q ? { url: q, name: q.split('/').pop() } : null));
