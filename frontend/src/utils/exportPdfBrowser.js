/**
 * 在浏览器内生成含中文的 PDF（使用 pdf-lib + @pdf-lib/fontkit，避免 jsPDF 对部分 TTF/OTF 解析失败：
 * metadata.Unicode / widths 缺失等问题）。
 *
 * 将中文字体放到 public/fonts/ 下（推荐静态 .ttf，不要用可变字体 VF）：
 *   NotoSansSC-Regular.ttf
 */

const FONT_CANDIDATES = [
  '/fonts/NotoSansSC-Regular.ttf',
  '/fonts/NotoSansSC-Regular.otf',
  '/fonts/SourceHanSansSC-Regular.otf',
]

function sanitizeFilenameBase(name) {
  const s = String(name || '法律文书')
    .replace(/[\\/:*?"<>|\r\n]/g, '_')
    .trim()
    .slice(0, 80)
  return s || '法律文书'
}

async function loadFontArrayBuffer() {
  for (const url of FONT_CANDIDATES) {
    try {
      const res = await fetch(url, { cache: 'force-cache' })
      if (res.ok) return new Uint8Array(await res.arrayBuffer())
    } catch {
      /* try next */
    }
  }
  return null
}

function wrapLines(font, text, fontSize, maxWidth) {
  const lines = []
  const raw = text == null ? '' : String(text)
  if (!raw) {
    lines.push('')
    return lines
  }
  let cur = ''
  for (const ch of raw) {
    const test = cur + ch
    const w = font.widthOfTextAtSize(test, fontSize)
    if (w > maxWidth && cur.length > 0) {
      lines.push(cur)
      cur = ch
    } else {
      cur = test
    }
  }
  if (cur.length > 0) lines.push(cur)
  return lines
}

/**
 * @param {string} title
 * @param {string} content
 * @returns {Promise<{ ok: true, blob: Blob, filename: string } | { ok: false }>}
 */
export async function tryExportPdfInBrowser(title, content) {
  const fontBytes = await loadFontArrayBuffer()
  if (!fontBytes) {
    return { ok: false }
  }

  try {
    const [{ PDFDocument, PageSizes, rgb }, fontkitMod] = await Promise.all([
      import('pdf-lib'),
      import('@pdf-lib/fontkit'),
    ])
    const fontkit = fontkitMod.default ?? fontkitMod

    const pdfDoc = await PDFDocument.create()
    pdfDoc.registerFontkit(fontkit)

    const font = await pdfDoc.embedFont(fontBytes, { subset: true })

    const margin = 48
    const titleSize = 14
    const bodySize = 11
    const a4 = PageSizes.A4

    let page = pdfDoc.addPage(a4)
    let pageHeight = page.getHeight()
    let pageWidth = page.getWidth()
    let yTop = margin
    const maxW = pageWidth - 2 * margin

    const newPage = () => {
      page = pdfDoc.addPage(a4)
      pageHeight = page.getHeight()
      pageWidth = page.getWidth()
      yTop = margin
    }

    const drawLine = (line, size, leadingMul) => {
      const lead = size * leadingMul
      if (yTop + lead > pageHeight - margin) {
        newPage()
      }
      const baseline = pageHeight - yTop - size
      page.drawText(line || ' ', {
        x: margin,
        y: baseline,
        size,
        font,
        color: rgb(0, 0, 0),
      })
      yTop += lead
    }

    const titleLines = wrapLines(font, title || '法律文书', titleSize, maxW)
    for (const line of titleLines) {
      drawLine(line, titleSize, 1.45)
    }
    yTop += titleSize * 0.35

    const body = content == null ? '' : String(content)
    for (const rawLine of body.split(/\r\n|\n|\r/)) {
      const wrapped = wrapLines(font, rawLine, bodySize, maxW)
      for (const line of wrapped) {
        drawLine(line, bodySize, 1.38)
      }
    }

    const pdfBytes = await pdfDoc.save()
    const blob = new Blob([pdfBytes], { type: 'application/pdf' })
    const base = sanitizeFilenameBase(title)
    return { ok: true, blob, filename: `${base}.pdf` }
  } catch (e) {
    console.warn('[exportPdfBrowser]', e)
    return { ok: false }
  }
}

export function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
