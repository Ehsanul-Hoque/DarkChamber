// Makes the PDFs in ../assets/pdf/ that the PDF renderer's instrumented test reads. Written by Apple's
// CoreGraphics PDF writer, never by this app, so a bug of ours cannot hide in them. Run on a Mac:
//   swift make-pdf-fixtures.swift ../assets/pdf
import CoreGraphics
import Foundation

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

// The plain file cut short: a PDF's cross-reference table is at its end, so without it nothing opens.
let plain = try Data(contentsOf: dir.appendingPathComponent("plain.pdf"))
try plain.prefix(plain.count / 2).write(to: dir.appendingPathComponent("truncated.pdf"))
