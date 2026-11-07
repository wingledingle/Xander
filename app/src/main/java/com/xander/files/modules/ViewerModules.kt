package com.xander.files.modules

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.ViewInAr
import com.xander.files.R

/** 3D model viewer (modules-web/www/modules/model3d, three.js bundled): turn, zoom, wireframe, lighting, animations. */
object Model3dModule : WebModule(
    id = "model3d",
    title = R.string.module_model3d,
    summary = R.string.module_model3d_summary,
    icon = Icons.Filled.ViewInAr,
    opens = MODEL_EXTENSIONS,
    defaultFor = MODEL_EXTENSIONS,
)

/** Quick Look (modules-web/www/modules/quicklook): SVG, fonts, Markdown and HTML previews, hex view and checksums. */
object QuickLookModule : WebModule(
    id = "quicklook",
    title = R.string.module_quicklook,
    summary = R.string.module_quicklook_summary,
    icon = Icons.AutoMirrored.Filled.ManageSearch,
    opens = setOf("svg", "ttf", "otf", "woff", "woff2", "md", "markdown", "html", "htm", "xhtml"),
    defaultFor = setOf("svg", "ttf", "otf", "woff", "woff2"),
)

val MODEL_EXTENSIONS = setOf("glb", "gltf", "vrm", "stl", "obj", "ply", "3mf", "fbx", "dae", "3ds", "amf", "gcode", "gco", "pcd", "xyz", "vox")
