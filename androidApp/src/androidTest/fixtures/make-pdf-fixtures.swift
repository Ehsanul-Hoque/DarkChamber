// Makes the PDFs in ../assets/pdf/ that the PDF renderer's instrumented test reads. Written by Apple's
// CoreGraphics PDF writer, never by this app, so a bug of ours cannot hide in them. Run on a Mac:
//   swift make-pdf-fixtures.swift ../assets/pdf
import CoreGraphics
import Foundation
import PDFKit

let dir = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

/// Three pages: A4 portrait red, A4 portrait green, A4 landscape blue, each a full-bleed colour.
func write(_ name: String, password: String? = nil) {
    var info: [CFString: Any] = [:]
    if let password {
        info[kCGPDFContextUserPassword] = password
        info[kCGPDFContextOwnerPassword] = password
    }
    let pages: [(CGFloat, CGFloat, CGColor)] = [
        (595, 842, CGColor(red: 1, green: 0, blue: 0, alpha: 1)),
        (595, 842, CGColor(red: 0, green: 1, blue: 0, alpha: 1)),
        (842, 595, CGColor(red: 0, green: 0, blue: 1, alpha: 1)),
    ]
    var box = CGRect(x: 0, y: 0, width: 595, height: 842)
    let ctx = CGContext(dir.appendingPathComponent(name) as CFURL, mediaBox: &box, info as CFDictionary)!
    for (width, height, colour) in pages {
        var page = CGRect(x: 0, y: 0, width: width, height: height)
        // The page's own box, as a String-keyed Data — the only spelling that reached the file when tried.
        let box: [String: Any] = [kCGPDFContextMediaBox as String: Data(bytes: &page, count: MemoryLayout<CGRect>.size)]
        ctx.beginPDFPage(box as CFDictionary)
        ctx.setFillColor(colour)
        ctx.fill(page)
        ctx.endPDFPage()
    }
    ctx.closePDF()
}

write("plain.pdf")
write("locked.pdf", password: "fixture")

// One 600×800 page, red over blue: a page drawn upside down, mirrored or at the wrong scale shows the
// wrong colour at the top or the bottom, which a single-colour page cannot.
do {
    var page = CGRect(x: 0, y: 0, width: 600, height: 800)
    let ctx = CGContext(dir.appendingPathComponent("halves.pdf") as CFURL, mediaBox: &page, nil)!
    ctx.beginPDFPage(nil)
    ctx.setFillColor(CGColor(red: 0, green: 0, blue: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: 600, height: 400)) // PDF space: y grows upward, so this is the bottom
    ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 400, width: 600, height: 400)) // and this the top
    ctx.endPDFPage()
    ctx.closePDF()
}

// Two 600×800 pages that a renderer must turn and crop, not just draw:
// 1. red over blue, with /Rotate 90 — shown 800×600, the page's top (red) down the right-hand side;
// 2. red all over with a green 400×600 rectangle at (100, 50), cropped to that rectangle — shown
//    400×600 and all green; any red is the bleed the crop box leaves out.
// CoreGraphics writes neither a rotation nor a crop box, so the pages are drawn by it and then turned
// and cropped by PDFKit, which writes the file: Apple's writer again, not this app's.
do {
    var page = CGRect(x: 0, y: 0, width: 600, height: 800)
    let drawn = NSMutableData()
    let ctx = CGContext(consumer: CGDataConsumer(data: drawn as CFMutableData)!, mediaBox: &page, nil)!
    ctx.beginPDFPage(nil)
    ctx.setFillColor(CGColor(red: 0, green: 0, blue: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: 600, height: 400))
    ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 400, width: 600, height: 400))
    ctx.endPDFPage()
    ctx.beginPDFPage(nil)
    ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
    ctx.fill(page)
    ctx.setFillColor(CGColor(red: 0, green: 1, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: 100, y: 50, width: 400, height: 600))
    ctx.endPDFPage()
    ctx.closePDF()

    let document = PDFDocument(data: drawn as Data)!
    document.page(at: 0)!.rotation = 90
    document.page(at: 1)!.setBounds(CGRect(x: 100, y: 50, width: 400, height: 600), for: .cropBox)
    precondition(document.write(to: dir.appendingPathComponent("turned.pdf")))
}

// The plain file cut short: a PDF's cross-reference table is at its end, so without it nothing opens.
let plain = try Data(contentsOf: dir.appendingPathComponent("plain.pdf"))
try plain.prefix(plain.count / 2).write(to: dir.appendingPathComponent("truncated.pdf"))
