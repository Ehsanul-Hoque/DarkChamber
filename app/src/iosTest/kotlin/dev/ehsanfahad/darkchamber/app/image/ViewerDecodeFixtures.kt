package dev.ehsanfahad.darkchamber.app.image

import kotlin.io.encoding.Base64

/**
 * Small images for the viewer decoder's tests, **made by other tools**, never by the code under test.
 *
 * The source is a 64×32 JPEG of four solid quadrants — red top-left, green top-right, blue
 * bottom-left, yellow bottom-right — written by Pillow. [ORIENTED] is a Pillow port of the
 * `recurser/exif-orientation-examples` generator script, run unchanged on it: copy *n*
 * is stored pre-turned and pre-mirrored so that it looks like the source **only** when a viewer
 * honours its Orientation tag *n* (0, outside the tag's vocabulary, means "as stored"). The generator
 * draws its edge and tag labels over the middle of each edge and the centre, so tests sample the
 * quadrants away from those.
 *
 * Pillow's own `exif_transpose` — a second, independent implementation — turns all nine back into
 * the 64×32 source with the quadrants where they started, which is the expected answer here. Copies
 * 5 to 8 are stored 32×64: a decoder that ignored the tag would get even the dimensions wrong.
 *
 * [HEIC] is macOS `sips -s format heic` on the same source.
 *
 * Every read decodes **fresh arrays**: the caller zeroes what it decodes, so a shared array zeroed by
 * one test would quietly become a different input for the next.
 *
 * Regenerate rather than hand-edit.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal object ViewerDecodeFixtures {
    const val WIDTH = 64
    const val HEIGHT = 32

    /** Index *n* carries EXIF Orientation *n*. */
    val ORIENTED: List<ByteArray> get() = listOf(
        // Orientation 0
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAAAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAgAEADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD4vorPsr/xTqGpGy074f6zqSy20lxYjQdOnv5pI47l7Vy8cMZMZ85AijJ3l0A+Zgta/ifwp8SvhxruneHv" +
            "in4J/sefWdMa/wBHeO7M0dxGiWzToSUQpJC13DG4wVLZMbyKNx/mqeQ5xTw0sRKjJQik2+lpaprvvra9utj/AG5wviz4cY3P" +
            "KWTUMzpSxVWdSnGmm+bnpNxnGSt7jvFqPPy+0teHMiCneGfBHjrxLpeheI7Txd4btl8T+ND4WstM1dLyJrbU5buSDToTLBDN" +
            "vF0kE8xkKqsCxbSWZlVm10vwY8J26r8QPivrHiXxavh74c+Cf7auvDOieOtT0u11nVNS1XS9HsbWYWk8Zit3nminuGhMcsgs" +
            "IlDqTvX0+EKOWYrMpYfG0ueMou3923vN6NNKye2vTqz4X6ROYccZFwVSzfhjHfVqtCtDmVk/aqp+6jBKUJxlLnnGylaC1k3e" +
            "MT6H+CH7Z9/8V/FejaTqfhTRbS119dONtFp2s3VxeWJ1DTb3UrMXCyWkUJ3W9hcb/Jlk8tzGPnDOY/fK8M+D/wAJ/DPgXxp4" +
            "K1aztbyO2ktvEug23hz/AISbVr7SPDuo6HNYIJtMg1G7uZLeK60/WofkllnkheKdUl8uYrXrPjvx9oXw70ePWddt9SuBcXcd" +
            "raWej6TcX11czPnbHFBbo8kjYBOFU4Ck9BX4D4l5DkuE4so4HhzDyVOpSpOCTlN1HUu1KCbcveTirOz5k9Fc/wCTrxf4Z4ew" +
            "PHGHy7hPCTVKrRoyhFOc3VlVTlGUFJyn70XGPK7PmT91XNqivBPD37dNnrnhHUPEdx8EPGFi66jDa6I93oF+ljei4ltoLZ5b" +
            "w2oitQ811DExbcoZ1CtIXXd03gP9p+XxT4m0Hwv4p+C/i3ww+uzPYQ3et6eYoRq0dvPcy2S7tryBYrW4PnhBGWhZMhwUHiYv" +
            "w544wOGq4jEYCpGFNNybWiSXM3vqkuqutGr3TR85jvCbxIy3CVsVisrqwp0U3OTjooxjzOW+sUtXJXWjV7ppfmF8G/2yPjZ8" +
            "DNCt/DHgs6QbGyaGWyEn2+3niuYby4u4bjz7O8hkLpJdTKFyIyj/ADIzKrryvxM+Mvjj4vxaUnjW00ZZNIa6MFxplnPFJKsy" +
            "Wy7H8yeRSqfZty7VVt00m5mARU5eiv8ArAy/wQ8L8r4qXEmFy/kxqnKp7RVa3xzcnJ8rqcmvM9OW2tkrH+kc8yxs6HsZT921" +
            "rWW33FiDRtbu9IvPENno1xNp+nSwx6jexKCls0qTPHv5zgrbzHIBAEZyRxn1r9jX4p2/hi/8e/BzxJ8PvFHibwn8Vfh0+keJ" +
            "bTwZYpcapYQw39pfWWr2sMjIlx9nvbSHMbyRpIkkieYjbWGZ+zP+0zf/ALN2p3moWfhvV783Ot6Jqqf2P4xbSN0um3E06W9w" +
            "Baz/AGm2laUCSPMZ+QENnGOwm/4KA+LLbxfB4x0DwPqtrIr+IbiS2Pj19ttNqkOnqLWzZbNTaabbSaZAYrPDny3aPzQVWQfi" +
            "HjLmnjbn9TNuGMFkCxGClKDo103GUeSNOopRamryjUi2npr7rT2fp5dTy2kqdaVW0uq+9dux+vn7Bv7Vn7Nep+Nb/wDag+Of" +
            "7T3gvw3Lpena450zxVr+nabqz6hrGo2Dapd3lhFeXCaYkX9naRYQ2ZubmaIQuZpA8wWvc/2hJ/2cf+CqXwY1b9n79nH9sTwZ" +
            "JrNnNHfT3mjSQ601nEVlt5DJaxXMLkbZZE3eYvlyhdwO0xn5P1//AII9/wDBQbxJealr9j4q8BaJqQ1W21TRlPxS1O8htrmL" +
            "T77Tk8kjRIWtXMeoS3k8yiQz3kRcLCJ2Mfs3/BOP/gnf+2l+zf8AtCWXxV/ab+JfhXXNP0rwLLotlJpPizUdSupJ2sPDtixK" +
            "3VlAsUcn9gm6ch2Pm3eza3l+c/8AzQ+JMfCuWYV+Mcj4oTzHCqm8JRhTj7OCoqCpU4pxaail1ur/ABJ2af7Hg/r3IsPVoe5K" +
            "/M7667s8c8Q/8Edf2qtX+MFz4Dt4/CK6BBplhofhn4nSafJnTNCsdctdctYZLNdSU3E4uLd4XiFvEH89pFvIkC2y+2/sVf8A" +
            "BHnxx+yB8W/A3jd/2qdO8Q6J4HTUBBpafD2Szvbz7Z/aksyPdHUpYxGbrVpZcCDeFtbaMOP3zzfc1FfjvEP0kPFniTJ5ZViM" +
            "XCNCdH2NSMaVP95Fx5ZSm5RlJTktG4OCW8VFno0snwFGpzqOqd1q9P68z//Z",
        ),
        // Orientation 1
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAEAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAgAEADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD4vorPsr/xTqGpGy074f6zqSy20lxYjQdOnv5pI47l7Vy8cMZMZ85AijJ3l0A+Zgta/ifwp8SvhxruneHv" +
            "in4J/sefWdMa/wBHeO7M0dxGiWzToSUQpJC13DG4wVLZMbyKNx/mqeQ5xTw0sRKjJQik2+lpaprvvra9utj/AG5wviz4cY3P" +
            "KWTUMzpSxVWdSnGmm+bnpNxnGSt7jvFqPPy+0teHMiCneGfBHjrxLpeheI7Txd4btl8T+ND4WstM1dLyJrbU5buSDToTLBDN" +
            "vF0kE8xkKqsCxbSWZlVm1nPf6f4U1RNU8TfFD4oeGfCgS8n8SXPw1vnnvIImijaRoLKbVbC2DSG2g3yF2P7mMmKUqMd3C7yi" +
            "WYOjmFPmjNWWtrO6d780bfffp1PlvHan4h0OEYZnwhjHQrYWbqVEoubqU+SUXFQVKq5NNppOPKleTd4pr69+CH7Z9/8AFfxX" +
            "o2k6n4U0W0tdfXTjbRadrN1cXlidQ0291KzFwslpFCd1vYXG/wAmWTy3MY+cM5j98rznwl+zL8K/gv8AtBfEHwj4Ev8AxRd+" +
            "H/APjBNH+H+l+JPFd9qEeiWA0iyYLFHcTSIshNzc5cDKrO8aFYztPWeO/H2hfDvR49Z1231K4Fxdx2tpZ6PpNxfXVzM+dscU" +
            "FujySNgE4VTgKT0Ffz/4jZVkVHjWWVcO4acVC1Nw96bnVTd3FNyk73ikurTaSuf8kHizknDWH8Q55Jwng5xVPlouF5Tc6yk0" +
            "3BOU5NNOMUurTairm1RXgnh79umz1zwjqHiO4+CHjCxddRhtdEe70C/SxvRcS20Fs8t4bURWoea6hiYtuUM6hWkLru6bwH+0" +
            "/L4p8TaD4X8U/Bfxb4YfXZnsIbvW9PMUI1aO3nuZbJd215AsVrcHzwgjLQsmQ4KDy8X4c8cYHDVcRiMBUjCmm5NrRJLmb31S" +
            "XVXWjV7po8XHeE3iRluErYrFZXVhTopucnHRRjHmct9YpauSutGr3TS/ML4N/tkfGz4GaFb+GPBZ0g2Nk0MtkJPt9vPFcw3l" +
            "xdw3Hn2d5DIXSS6mULkRlH+ZGZVdeV+Jnxl8cfF+LSk8a2mjLJpDXRguNMs54pJVmS2XY/mTyKVT7NuXaqtumk3MwCKnL0V/" +
            "1gZf4IeF+V8VLiTC5fyY1TlU9oqtb45uTk+V1OTXmenLbWyVj/SOeZY2dD2Mp+7a1rLb7ixBo2t3ekXniGz0a4m0/TpYY9Rv" +
            "YlBS2aVJnj385wVt5jkAgCM5I4zHY+EfFnxAS48M+BfC+oaxqFxpUtzFaabZvNI1v9zzgqgkx7mC7+mTjNel/sz/ALTN/wDs" +
            "3aneahZ+G9Xvzc63omqp/Y/jFtI3S6bcTTpb3AFrP9ptpWlAkjzGfkBDZxjsJv8AgoD4stvF8HjHQPA+q2siv4huJLY+PX22" +
            "02qQ6eotbNls1NppttJpkBis8OfLdo/NBVZB+WeIfGvj9h84zTJ8lyGOIwsrxpVmmvclSjzK3tI8z5uez01tGz0O7CYbKnTh" +
            "UqVbS6rzv6H7efsJf8FDP2RbzwT8Rfit8Q/jf4X8Bt4j8dz65/wjPjTxZplvqul2otNP08reRQ3MqQSieAq0W8vH5kYkCO2w" +
            "dn+0JP8As4/8FUvgxq37P37OP7YngyTWbOaO+nvNGkh1prOIrLbyGS1iuYXI2yyJu8xfLlC7gdpjPyfr/wDwR7/4KDeJLzUt" +
            "fsfFXgLRNSGq22qaMp+KWp3kNtcxaffacnkkaJC1q5j1CW8nmUSGe8iLhYROxj9m/wCCcf8AwTv/AG0v2b/2hLL4q/tN/Evw" +
            "rrmn6V4Fl0Wyk0nxZqOpXUk7WHh2xYlbqygWKOT+wTdOQ7HzbvZtby/Of/l78QcD4S0M4zDjnIuKFLNY1XiKNOME4+0vGSil" +
            "KLUlva910knZ3/asJLHunDDVaHuWs3foeOeIf+COv7VWr/GC58B28fhFdAg0yw0Pwz8TpNPkzpmhWOuWuuWsMlmupKbicXFu" +
            "8LxC3iD+e0i3kSBbZfbf2Kv+CPPjj9kD4t+BvG7/ALVOneIdE8DpqAg0tPh7JZ3t59s/tSWZHujqUsYjN1q0suBBvC2ttGHH" +
            "755vuaivyfiH6SHizxJk8sqxGLhGhOj7GpGNKn+8i48spTcoykpyWjcHBLeKizvpZPgKNTnUdU7rV6f15n//2Q==",
        ),
        // Orientation 2
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAIAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAgAEADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD1iivKvHn7T8vhbxNr3hfwt8F/Fvid9CmSwmu9E08ywnVpLeC5ism27njDRXVufPKGMNMqZLkIeZ8Q/t02" +
            "eh+EdP8AEdv8EPGF87ajNa629poF+9jZC3luYLl4rwWpiugk1rNEpXapZGDNGUbb/nJhPDnjjHYaliMPgKkoVEnFpaNNcye+" +
            "ia6uy1SvdpH+S2B8JvEjMsJRxWFyurOnWScJKOkoyjzRlvpFrVSdlqle7SfvdeB/G/8AbPv/AIUeK9Z0nTPCmi3droC6ibmL" +
            "UdZure8vjp+m2WpXgt1jtJYRtt7+32edLH5jiQfIFQyexeBPH2hfETR5NZ0K31K3FvdyWt3Z6xpNxY3VtMmN0csFwiSRtgg4" +
            "ZRkMD0NeO/GzwR8N9R8XfEjWfGsV3IIvC2hbvC8vifWbHRtVsry6urbVdb1K20q8tZr6GwhtdOhdY5ovLW9jkuJPJjTZ73hh" +
            "w9k+YcYVcu4gw0nGNOo3F88ZRnCzd4xtNtJSXKk3dp8rsfT+DfCuQZpx7WyninCTlGFKq3B88JQnTtJ3jFqpKSipLkinLmaf" +
            "K7Hyr4m8EeOvDWl674ju/F3hu5Xwx40Hha90zSUvJWudTiu44NRh82eGHYLVJ4JhIFZZ1l2gqysqtqbxv4j8CfEDxRpfiP4e" +
            "+L/HE+m3vhfTr3W/DHiHxNrd9YaDrcU+oQS2lpJqDlb21Ee28t5d07RDU5IzMzKxqGv3niyOU0cxVDAU+WMIq7TvzN63vd9G" +
            "t9ej2R/1k/R+reIOZcG1M14uxbrVsRVk4RlFRdKELU3FxVOmk3OEn7qcGrTi7ykFFT+GPCnxK+I+u6j4e+Fngn+2J9G0xb/W" +
            "HkuzDHbxuly0CAhHLyTNaTRoMBQ2DI8ancMi9v8AxTp+pCy1H4f6zpqxW0dxfDXtOnsJo45LlLVCkc0YMh85yjDI2FHB+ZSt" +
            "edDIc4qYWOIjRk4STafS0Vdt9ttL2v0ufZYrxZ8OMFnlXJq+Z0o4qlOnTlTbfNz1WowjFW993klLk5vZ3vPlR+wX7av/AAR5" +
            "8cftf/Fvxz43T9qnTvD2ieOE08T6W/w9kvL2z+x/2XLCiXQ1KKMxi60mKXBg3lbq5jLn9y8PiXh7/gjr+1VpHxgtvAdxH4Rb" +
            "QJ9Mv9D8TfE6PT5M6noV9rl1rl1DHZtqTG3nNxcJCkRt5QnkLI15Kha2b9U6K/grh76SHizw3k8cqw+LhKhCj7GnGVKn+7io" +
            "8sZRcYxk5xWic3NPeSkz/Ietk+ArVOdx1bu9Xr/XkfLP7Pc/7OP/AASt+DGk/s/ftHftieDI9ZvJpL6C81mSHRWvIgsVvGY7" +
            "WW5mcDbFGm7zG8yUttA3CMfOX/BSv/goJ8NvG2uWOg+DvGnw48X+AG0Kz1Pwvr/hbTtL8R67HrLXV3byGyI8Z6DeWVwvlwpA" +
            "bNJ5ZmeZVdShjPof/BRz/gnf+2l+0h+0Je/FX9mT4l+FdD0/VfAsWi3smreLNR026jnWw8RWKkLa2U6yxx/28LpCXU+babNq" +
            "+Z5yeM6B/wAEe/8AgoN4bvNN1++8VeAtb1I6rc6prKj4panZw3NzLp9jpz+cTokzXTiPT4ryCZhGYLyUOVmECmT9g8OMN4O1" +
            "MywvGnEfEUJ5nWcqtenUg4xjVm3JuLpezcJx3jJPlvoo6pLz8Y8wUJYajR9xaJp9Pnc/Kb9uTV/gpqnxH8PXnwr8F+ONH1df" +
            "AttL8RLrxp4AsvDP9samlxNGdYh06z1C9jtkni8gTESKjXEckgAMrAeRT6NrdppFn4hvNGuIdP1GWaPTr2VQEuWiSF5NnOcB" +
            "biE5IAIkGCece7w/8FAfFlz4vn8Y6/4H1W6kZ/D1xHbDx6+25m0uHUFNreM1mxu9NuZNTnMtnhD5aLH5pLNIeP8A2mP2mb/9" +
            "pHU7PULzw3q9gbbW9b1V/wC2PGLavtl1K4hne3twbWD7NbRNERHHmQ/OSWznP/S34KZl46cORyjhTMsmlTwVNy9pXqPmkqbj" +
            "UnGOkrRUZOMY6OyShbQ/G8yhllb2leFS8nsl30X9fecx8M/jL44+EEWqp4KtNGaTV2tTPcanZzyyRLClyuxPLnjUK/2nc25W" +
            "bdDHtZQXV+q+Mn7ZHxs+OehXHhjxodIFjetNLeiP7fcTy3M15b3c1x595eTSB3ktYVK5MYRPlRWZnby2iv3TMPBDwvzTip8S" +
            "YrL+fGucantHVrfHBxcZcqqcmnKtOXlsrNWPMhmWNhQ9jGfu2tay2+4//9k=",
        ),
        // Orientation 3
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAMAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAgAEADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD9iKK+Gf21f+Cw3jj9kD4t+OfBCfsrad4h0TwOmnmfVH+IUlne3n2z+y4oXS1GmyxiM3WrRRZM+8La3MhQ" +
            "fuUm8S8Pf8Fiv2qtX+MFt48uJPCK6BBpl/rnib4Yx6hJnTNCsdcutDupo7xtNU3E4uLdJklNxEH89Y2s4kDXK/468PfRv8We" +
            "JMnjmuHwkI0J0fbU5Sq0/wB5Fx5oxioylJTktUpqCW0nFn6DWzjAUanI5ap2ej0/ryPY/wDgo5/wUQ/bS/Zv/aEvfhV+zJ8N" +
            "PCuuafpXgWLWr2PVvCeo6ldSTtYeIr5QGtb2BYo5P7BFqgKMfNu9+5vL8l/GdA/4LCf8FBvEl5pugX3hXwFompDVbnS9ZYfC" +
            "3U7yG2uYtPsdRfzgdbha1cx6hFZwQsZDPeRBC0InUR/WH7PcH7OP/BVL4MaT+0D+0d+x34Mk1mzmksYLPWY4daaziKxXEYju" +
            "pbaFyNssb7fLXy5Q20naJD86f8FF/wBnH9l/QtW1L4T+BfgR8Pfh74e8C6B4cuL7xJpGj6Zot5BLrOpaolrP/a89ndR6JY2k" +
            "mnXEr3v2W4lE95CkXlEuzftPhfS8MMVmeH4LzjhZSzPCqosVVnUi4L2Sl7Sb5ZS57K3uxT11jeyZ52NeNjB4mnX9yVuVW77H" +
            "5Nw/8E/vFlt4vn8Ha/441W1kV/D1vHcjwE+22m1SHUGN1eK14ptNNtpNMnEt5lz5brJ5QKtGOP8A2mP2Zr/9m7U7PT7zxJq9" +
            "+bnW9b0p/wC2PBzaRul024hge4tybqf7TbStKTHJiM/IQVznB+1HZ/CbWbnwb8WfhD8dtV8Yab438GPJe6L4s8T22p+IPCs1" +
            "vf3FtNpOoSWyxrJEzRLdQSGGATRXKt5S7cnzifWdbu9Is/D15rNxNp+nSzSadZSsCls0qQpJs4zgrbwjBJAEYwBzn/pT8Fsv" +
            "8a+I55VxPjM/+sYKU5xrUGnGSUI1KbT9xKUlUjG9rXfvKT3PxzMZ5bRU6EaVpaWf3Pv2K9FdR8M/g144+L8Wqv4Ku9GWTSGt" +
            "RPb6neTxSSrMly29PLgkUqn2ba25lbdNHtVgHZOq+Mn7G/xs+BmhXHifxoNINjZNNFemP7fbzxXMN5b2k1v5F5ZwyF0kuoWL" +
            "YEZR/ldmVkX9zzDxv8L8r4qfDeKzDkxqnGn7N0q3xzcVGPMqfJrzLXm5bO7djzIZbjZ0PbRh7tr3utvvP098efswS+KfE2ve" +
            "KPC3xo8W+GH12ZL+a00TUDFCNWjt4LaK9bbteQLFa248guIy0KvgOA45nxD+wtZ654R0/wAOW/xv8YWLrqM11raWmv36WN6L" +
            "iW5nuUisxdCK1DzXU0qhdyhnYsshdt3vdFf8n+E8RuOMDhqWHw+PqRhTSUUnoklypbapLo7rRO10mf5uYHxZ8SMtwlHC4XNK" +
            "sKdFJQipaRjGPLGO2sUtFF3WidrpNYvgTwDoXw70eTRtCuNSuBcXcl1d3msatcX11czPjdJLPcO8kjYAGWY4CgdBXmPxL+ON" +
            "j8Kfij451XQvF/iLw1qzfDe009PiP4f0fWmg8JpBqttqGoWd3qGk281zpy39l5SfaLdJZ4AiEoqzhj7RXgfxv/Ywv/iv4r1n" +
            "VtM8V6LaWuvrqIuZdR0a6uLyxOoabZabeG3aO7ihO63sLfZ50UnluZD84ZBH73hfxDlGW8aSzXP8TKK5Kj5/elKU5rld5RTm" +
            "m05PmVnzJe8rn0/gzxVkWU+IU864nxk4L2dV8/vzlOpNcr5pRUqibjKclNNPmS95XPljxr4Y8GfDzxLpfh/wbonj+eKHw/bx" +
            "+KfG/inRPEMNp4l1+7vdSu5Lu3/tZA1vB5W20i3rBLcNp88rxb2JMVO8TeN/HXiXS9d8OXfhHw3bL4n8aDxTe6npL3kTW2py" +
            "3cc+ozeVPNNvF0kEEIjDKsCxbgGZmZm1+9cXVMrxOZrE4GpzqaTfk1p1SeqSet23dvdH/WJ9HjB8eZLwRPJuKcG8PUw1SUab" +
            "unz05JVL3jOcfdlKUUocsIxUYRScZE/hjxX8Svhxruo+IfhZ42/sefWdMWw1hJLQzR3EaJcrA4AdCkkLXc0iHJUtgSJIo2nI" +
            "vbDxTqGpC91H4gazqSy20dvfHXtRnv5pI47lLpAkk0hMZ85C7HB3l3J+Zi1aFFebDPs4p4WOHjWkoRTSXS0lZp999L3t0sfb" +
            "4rwm8OMbnlXOa+WUpYqrOnUlUafNz0mpQlF39x3inLk5faWtPmR//9k=",
        ),
        // Orientation 4
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAQAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAgAEADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD8c6K9S+Df7G/xs+OehW/ifwWNIFjetDFZGT7fcTy3M15cWkNv5FnZzSB3ktZmDYMYRPmdWZUblfiZ8GvH" +
            "Hwgi0p/Gt3ozSau10ILfTLyeWSJYUtm3v5kEahX+07V2szboZNyqCjP/ALS5f43+F+acVLhvC5hz41zlT9mqVb44OSkuZ0+T" +
            "TlevNbS6dj85nluNhQ9tKHu2ve62+86f9mf9ma//AGkdTvNPs/Emr2Bttb0TSk/sfwc2r7ZdSuJoEuLgi6g+zW0TRAySYkPz" +
            "gBc4z2E3/BP7xZc+L4PB2geONVupGfxDbyXJ8BPtuZtLh09hdWareMbvTbmTU4BFeZQ+WjSeUSyxnwiDWdbtNIvPD1nrNxDp" +
            "+oywyajZRMAly0STJHv4zgLcTDAIBEhyDxjvP2cfgt+0V8Ub/wAReJf2dry100+DPDsc3iXXr/4j6P4Zg0zTJrpIUV7rVb21" +
            "jETXDRrsViA7pkAsufxrxXyPxmyvH5nxDh+JqeAwClFUoVG+VRlCENWqcuVublayk72lfY9HA1cunCFJ0XKXVr/h+x+vWv8A" +
            "/BYT/goN4bvNS0Cx8K+Atb1I6rbaXozH4W6nZw3NzLp99qKeSBrczXTiPT5bOeFTGYLyUoGmEDCT2b/gnH/wUQ/bS/aQ/aEs" +
            "vhV+038NPCuh6fqvgWXWrKPSfCeo6bdRzrYeHb5gWur2dZY4/wC3jauAinzbTfuXzPJTzb/gnn+zb+y/o/xB0/8AZq/aA+Dn" +
            "gD4g33xN+EmneNdN1Cc+Dtfe0L3DvqFvNPpUj3Etu8v2GSG9nFwtzJHL/pszoAPpP9oSD9nH/glb8GNW/aB/Zx/Y78GR6zeT" +
            "R2M9no0cOiteRBZbiQSXUVtM4G2KR9vlt5kpXcRuMg/5oPFXC+GmXZziuBcs4U5M1r8kcNUjNKLdVR5JJylFRtrFp+7zJ3er" +
            "Z+yYGWMnTjiZ17wW6t23Pqaivys8Q/8ABYr9qrSPjBc+PLeTwi2gT6ZYa54Z+GMmoSZ1PQr7XLXQ7WaS8XTWNvObi4eZ5RcS" +
            "hPIaNbOVCty3tv7FX/BYbxx+1/8AFvwN4If9lbTvD2ieOE1AwaonxCkvL2z+x/2pFM72p02KMxi60mWLIn3lbq2kCH98kP4p" +
            "xD9G/wAWeG8nlmuIwkJUIUfbVJRq0/3cVHmlGalKMnOK1agpp7RcmelSzjAVqnIpat2Wj1/rzPx9srDxTp+pG9074gazpqxW" +
            "0lvYnQdRnsJo45Ll7pw8kMgMh85w6nA2FEI+ZQ1a/ifxX8SviPruneIfin42/tifRtMaw0dI7Qwx28bpbLO5Bdy8kzWkMjnI" +
            "UNkRpGp2iCiv71nn2cVMNLDyrScJJJrpaOiS7ba2tfrc/wBeML4TeHGCzylnNDLKUcVSnUqRqJPm56rcpyk7++7ybjz83s72" +
            "hyoKl8H+MfGvhDxfF43+HXw11zxHPp8N5Z6hYJ4N1G/0nW9PeOKPUdKvHt4WSS2lhuYoplDbo2mhddsixERU7wz438deGtL0" +
            "Lw5aeEfDdyvhjxofFNlqerveStc6nFdyT6dMYoJodgtUnnhMZZlnWXcQrKrL28L/ANm08e6+Mrul7NXi1u5XSt8Mrq17q2q8" +
            "rny/jq+NcXwlDKuHMqhj3ipunVhU+CNLklJyv7Wlyy5lHknzWUl0k4n2V8KPi78AfEXxB8N6f8DdM8a6b4Tufgv4S0jwk3jf" +
            "w5e20k0tmmpyzWa3ksKW99LDFKuZoGZJlR5Iyyq+30Lx34B0L4iaPHo2u3GpW4t7uO6tLzR9WuLG6tpkztkint3SSNsEjKsM" +
            "hiOhrx34IfsYX/wo8V6Nq2p+K9Fu7XQF04W0unaNdW95fHT9NvdNszcNJdywjbb39xv8mKPzHEZ+QK4k98r8G8Ts9yfMOOHn" +
            "GQYmUueMJuaUoOFSK5bQuoyVoxi79JN2bsf8k/jHxLkGaeI7z/hjGTlzxpzc0pU3CrFctoXUZK0YQlfpJtJux4J4e/YWs9D8" +
            "I6h4cuPjf4wvnbUYbrREu9fv3sbIW8ttPbJLZm6MV0EmtYZWDbVLIpVYyi7em8B/swS+FvE2g+KPFPxo8W+J30KZ7+G01vUD" +
            "LCdWkt57aW9XdueMNFdXA8gOYw0zPguS59VorxcX4jccY7DVcPiMfUlCompJvRprla20TXRWWrdrts+dx3iz4kZlhK2FxWaV" +
            "Z06yanFy0cZR5XHbSLWjirLVu122/wD/2Q==",
        ),
        // Orientation 5
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAUAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCABAACADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD4vrPvrLUtQ8U+H9OsntpV1LWbbSxY3lxcwxyTXU8UMche1dZcoWOEXcX3YCltorQqfwp4n134cfErRPin" +
            "4e8O6ZrM+j+aj6PqzpFHcRyFCSk7W87W8imNcSIm4qZIyQsjZ/mrIZ4annFGWIkowUtW1zK3Zp332v0vfof7c+LOFzzG+HGZ" +
            "0MmpTq4qVJqnGnUdKfNdWlGcXF3h8fKmvacvI9JHh37ZHwb0L4GfGw+C/DGntZWJ0jeLK4tLy2uYp4r++s5/Phu55nicyWrb" +
            "QJGQxrG3yszqvltdR8ZfiZF8X/HEXjVPCraRIujQ2VxAdTS5WWRJ7iTzF228PlqVmRdreY25GbfhlROXr/o68EMv4qyvwvy/" +
            "C8SKaxsPaqp7STnP+NUceaTlK/ucttWrWS0P8AMynQnjZyo25dLW22R9eUU7wR4Z0vxL461e08R+BPGnidLbw2t/pll4Ba7u" +
            "NTtmS8t4JTFp0EkaX4YXKF2mkRYFQsu5mVW7LwF+zH8R/FWtpoMnxV+FPhmLVdS8TTeFz8QPiUYbm60fStT1W1lvGjtLGWfy" +
            "Yl0m9DXElvCHFpK+1QDX/OzR4PzXF5bSxmFtUU+idmtWteay3Vt99rrU/wBxMy+kbwHw7xtj+G8+VTCSwuvtJR54VLxhJKKp" +
            "c8+ZxnzJcvwpuTjL3T4X0aDSLvW7Oz8Q6xLp2nzXAS91GGzS4a2Qg/P5byxKw3bQcyKACWzxg+j/ALTP7M+p/s3X+m2eoazr" +
            "dyb/AFfWdP2a/wCCrjRJW+wNaAXUCTyu1xbTfajslAXmFgRngZnh/wDZy8X+Mvgn4K+NPhTxV4V1SDxb4uHhfVNBluLmO58M" +
            "6tI87WdvqSPCuYbu2t5biOaBpFKxzRnZJGVr0P8A4KAzeL7bxZoegeMbF1ktdV1420l58QYdemtl32KtYW4Wxs3stNgKr9mh" +
            "MbR5mn2MpWRR/tvmvjJVz3xwyLL+Gs1m8HXbjXoygoWlBzVmqtONSMpJXs3d2TitbH+J1PL1SyyrOtD3ls737dnY+6f2z/gh" +
            "4r+K9/pWp6T4OXX7W00W8tIrY+HdN1c2N5LdWUi3Qs9Su7WCTMMFxD5nm74/O4Rg5eOP4afs3+OdLs7PXdS8FeE7CDQ7W80/" +
            "w14FnaUWc2jXer+Lbu60y+aBm8kTWHi6908m3aQQiNJEaUjB+gKxfH3jvR/h3oUeu6zbXdwLjUrTT7O0sIPNmubq6uI7eCJF" +
            "yAWaWRF5IAzkkCv+cfJfErjPC5JheHsrUUo1IuHLC9SUvae0jF3bT9+2iim17rbR/nTw74veIGC4dwfCuTKCUKsXT5KfNVnP" +
            "2qqxi7tp/vLaKKclaLbWh1H7VH7CPjvVP2b/AIlfGL9pGWy0C28IeB73WNKuL/4k6h4r1S41Sw02/h025l1A6fZfY4bGO/1E" +
            "28cFtJMZ757l5WdQh4v/AII96/eeJP8AgoNqtjr/AIk1UalongLxSV0bXtO0+3ubaG41Pw+DBs068u0LwPbBZLm6dLyczIrG" +
            "cQFo/rCf9oT4Mf8ABVL9nH4xfs4/s/eLJrPWZPBjadeT6rbKYrNtRhu4rWUyW8kgI3202Qj+bHsDYXdGT5r/AME7/wDgnH+0" +
            "J+zf+2l4m/ab+Kui+BdKsNc8K6tYSWXhe5sHnkurzUbC6UlrHQtL86NFtZQZLnzpcyKU8vfNv+cfiXmFTws4oyTjGtHCZi1T" +
            "hRwns1RUYcsZKNOly2Skm5PW9mpK10z/AF5+pwWOoVMOuaGrcr3182ef14J+3T4e8I65Z+CLjxH4g1FXsfGGnzvolhZS3gvb" +
            "Fb+0N5M9tBbzyyCGEbi0cbMFd1CsZNre915V+0/4D8TeKZfBfinwv4Dm11/DHi231K7h0ia3h1YQxndstJbmaGNA8ixiXMqM" +
            "Yw2xg4Qjq8OcXhsDxxgMRiKqpwjUTcm0klru5aJPZ7aN2admf4zeE2OwmW+JGV4rFVlRpwqxcptxjFLX4pS0UXtJu2jdmnZr" +
            "sv8Agjr4h+MGr/tVLb+BNT0yDQF8I27/ABO8M+FdB1zS9C0w+Zqq2csVrrltFOs8pSyWOSJpN4t9RWQhIrZV/VOvhn/gjz+x" +
            "V8W/2QPHHxVfxv8AAxPA+ieIdO8PLpcA1/8AtL7Ze28mqm6kSaXUb64aMR3FmB5rwqH8wRw8PNN9zV+IfSQ4hyfiTxZxeIyq" +
            "VGdCMKUY1KPK41P3cZOcpR0lNSk4NrZQUXrE/wBqsno1KOAip3vro+mv9M//2Q==",
        ),
        // Orientation 6
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAYAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCABAACADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD1ivKv2n/HnibwtL4L8LeF/Hk2hP4n8W2+m3c2kQ282rGGQ7d9pFcwzRuEkaMy5idhGW2KXKA+q14J+3T4" +
            "h8I6HZ+CLfxH4f1FnvvGGnwPrdhey2YsrFr+0F5C9zBcQSxiaE7SscisVR2DKY9y/wCcnhzhMNjuOMBh8RSVSEqiTi0mmtd1" +
            "LRpbvfROybsj/JbwmwOEzLxIyvC4qiq1OdWKlBqMota/FGWjit5J30Tsm7J/Yv8AwR5/bV+Lf7X/AI4+KqeN/jmnjjRPD2ne" +
            "Hm0ucaB/Zv2O9uJNVF1GkMunWNwsZjt7MjzUmUv5hjm5eGH7mr8rP+COvh74waR+1Utx4E0zTJ9Abwjbp8TvE3hXXtc1TQtT" +
            "Pmaq1nFFda5cyztPEXsmjjiWPYLjUWkBSW2Zv1Tr8Q+khw9k/Dfizi8PlUaMKEoUpRp0eVRp/u4xcJRjpGblFzaW6mpPWR/t" +
            "Vk9apWwEXO99dX11/pH5n1i+PvAmj/ETQo9C1m5u7cW+pWmoWd3YT+VNbXVrcR3EEqNggMssaNyCDjBBFeO/tn/G/wAV/Ci/" +
            "0rTNJ8YroFrd6LeXcVyfEWm6Qb68iurKNbUXmpWl1BHiGe4m8vyt8nk8OoQpJl+Hf2jfipd2Pg5LT4veA7i08S694iEes3mg" +
            "3t4sHhHSj4gMviq6uLV4Yc7fD18U0+GItdC1ldZLUMiV/R/DfhbxrmmU4bPsqlD3pNwtU5ZxcJNJ6pJPmVo2k3drRXP8f+Ev" +
            "BbxEzrI8HxNkk6dpzbharyVIShNxjK7Sim5xtHlk5XcW0rn6NT/s9/Bj/glb+zj8Yv2jv2fvCc15rMfgxtRvINVuVEV42nQ3" +
            "ctrEY7eOMAb7mbJRPNk3hcttjA81/wCCd/8AwUc/aE/aQ/bS8TfsyfFXWvAuq2Gh+FdWv5L3wvbWCTx3VnqNhaqCtjruqeTG" +
            "63UpMdz5MuY1CeZsm2cV+1X+3V8RdC/Z5+JfwV/aXsYdatvFvgi80fTLTV/h5c+GtVt9T1DTNRm0y3ks4dSvlvYL5NN1JYGg" +
            "uIp1nsjbyRBpFkHHf8Ee9AvPDf8AwUG1W+1/w3qp1LW/AXikLrOvajp9xc3MNvqfh8mffp1naIHne5DSW10j3kBhRmEAnKyf" +
            "Kvw0x1Pws4nzzjCjHGZjy06lHF+0VZTp8sYqVOrzWajZxel7JRV7JH+t/wBci8dRpYd8sNU42tr5o/P/AMEeJtL8NeOtXu/E" +
            "fjvxp4YS58NrYaZe+AVu7fU7lnvLeeURajBHIlgFFsgdZo3WdXKrtZVZe0+C/wC0x4v+GHi6XxTq3wY+HXj7T7Lxr4h1jwpp" +
            "3xE8M3KXGjwald3TSwW81lfJJHa3EM7GawuHu4CZ5gQTJIz8TRX9SUOMM1weXUsJhUqap9Urt773urO99t9rbH+pma/Rw4D4" +
            "l4yzDiHPpVMVLFpL2cpKEKdlTScHTUJ80VTUU+bWDakpO8n5V8Yv219X8dyaBpPwn+Afwz+E/hfw34pj8SWvhbwjp14bO91V" +
            "FMcd5qM91cTXFwIo3ljRFdI4o5pdiAuxOx/wUBh8X3PizQ9f8Y3ztJdarrwto7z4fQ6DNcrvsWa/tyt9ePe6bOWX7NMZFjzD" +
            "PsVi0jHwjRp9ItNbs7zxDo8uo6fDcB73TobxLdrlAD8nmPFKqjdtJzGwIBXHOR6P+0z+0xqf7SN/pt5qGja3bGw1fWdQ36/4" +
            "1uNblX7e1oRawPPEjW9tD9lOyIFuZmJOeT/ttmfgzPh7xryDGcL5TKGCwyvWrOcZJuTm7t1KkqkpRvq2rq6UNlb/ABQhmKrZ" +
            "bVjXqe89l93ZWPban8KeGNd+I/xK0T4WeHvEWmaNPrHmu+sasiSx28cZQEJA1xA1xIxkXEaPuCiSQgrG2IKz7691LT/FPh/U" +
            "bJLaJdN1m21QX15b3M0cc1rPFNHGUtUaXLlTh12lNuQwbaa/w1yGGGqZxRjiIqUHLVN8qt3bdtt7dbW6n+9nizis8wXhxmdf" +
            "Jqs6WKjSbpyp03VnzXVoxhFSd5/BzJP2fNzvSJ89/GX4ZxfCDxxF4KTxU2ryNo0N7cTnTEtlikee4j8tdtxN5ihYUbc3ltud" +
            "l2YVXfl69S/bI+MmhfHP42Hxp4Y1Br2xGkbBe3F3eXNzLPLf315P5813BC8riS6baRGqCNo1+Zldm8tr/o68EMw4qzTwvy/F" +
            "cSObxs/aup7SLhP+NUUeaLjG3ucttErWa0P8AMyhQhjZxo25dLW22R//2Q==",
        ),
        // Orientation 7
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAcAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCABAACADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD9iK+Gf+Cw37avxb/ZA8cfCpPBHxzTwPoniHTvELapOdA/tL7Ze28mlC1jeGLTr64aMR3F4T5SQqH8syTc" +
            "JDN9zV+Vn/BYrw98YNX/AGqmuPHemaZBoC+EbhPhj4m8Va9rml6Fph8zSmvIpbrQ7mKdZ5Sl60kcqybxb6c0YCRXLL/jr9G/" +
            "h7J+JPFnCYfNY0Z0IwqylTrcrjU/dyioRjLSU1KSmk9lByWsT9BzirUo4CThdPTVdNf6Rxv7MHjzxN4pl8aeFvFHjybXX8Me" +
            "LbjTbSbV4beHVhDGdu+7itoYY0DyLIYsRIxjC71DhwPVa8E/YW8Q+Edcs/G9v4c8P6ir2PjDUIE1u/vZbwXtit/dizhS5nuJ" +
            "5ZBDCNoWSRmCujFmMm5ve6/b/EbCYbA8cY/D4ekqcI1GlFJJJabKOiT3W2jV0ndH+KvizgcJlviRmmFwtFUacKslGCUYxitP" +
            "hjHRRe8UraNXSd0vQP8Agoj/AMFHP2hP2b/20vDP7Mnwq1rwLpVhrnhXSb+O98UW1g88l1eajf2rALfa7pfnRotrERHbedLm" +
            "Rg/l74d/pUH7PfwY/wCCqX7OPwd/aO/aB8JzWesyeDF1Gzg0q5UxWbajDaS3UQjuI5ARvtocF082PYVyu6QH5P8A+CwmgXni" +
            "T/goNpV9oHhvVRqWieAvCwbWdB1HT7e5tobjU/EBE+/UbO7QvA9sWjtrVEvJzM7KJxAFj+lv2G/25f2TvAn7FPwj8AP8QtMH" +
            "iiy8AabYn4a+FYLrVdfhnt4FgkgGlQCe/V1ljZGR0YowKsxwTXzOfcJYnI/CLhTP+EMFUp5rVc1Kth1J1Zc0Zq3uNt8yulpt" +
            "dO12n/s9SxEamPr0sRJOC6Pbp3PnXwD4E0f4d6FJoWjXN3cC41K71C8u7+fzZrm6uriS4nldsAFmlkduAAM4AArarx/41/En" +
            "xb4U0rxJF4o+Nnhj4b694Zg0i91PwPJFDqur6dpd14l0TRbu71S7jd7HR54U1qKdLNvtU2AHnWJf3b4f7GHxv8V/Fe/1XTNW" +
            "8Yrr9raaLZ3ctyPEWm6ubG8lur2NrU3mm2lrBJmGC3m8vyt8fncuwcJH+iZ/4X8a5fkNbiLNnFaqU1KbdVuc7Xdk1fmd2nLm" +
            "30umj/IjijwZ8RMq4ZxHFmeShHVSnGdRyrN1JqLbSTjzc0ryTnzavS6aXwt/wT+h8X23izXNf8HXzrJa6roIuY7P4fQ69NbL" +
            "vvmW/uC19ZvZabAVb7TMJGjzNBvVSsbDj9A/bc/a68KeDPFXw6+H37RviLwx4b8beJ7nX/E2i+B9Wl06z1C9njjilffEVneF" +
            "o4Y1EbyFNq8rlmyfszftMan+zdf6leafo2t3Jv8AV9G1DfoHjW40SVvsDXZNrO8ETtcW032ob4iV5hUg55HnGsz6Rd63eXnh" +
            "7R5dO0+a4L2WnTXiXDWyED5PMSKJWG7cRiNQAQuOMn/o+4c8JquP8ec8zbiXJY1sHW96hUqRpTp80eTVw5m7yV7Nwb0fNvc/" +
            "0NrY9QyulTo1LSW6V7/18z6x8Hazf/CXVBc+DdA0/wDsq/t9M0zW7NdPMjw2MPijQNdkmgiWaESSBtERTGzpvWZwHVtrCXxv" +
            "4m0vxL460i78OeO/GnidLbw21hqd74+W7uNTtmS8uJ4hLqM8caX4YXLhFhjRYFQK25mZmbRX+GtPibGrJJ5ZUipQlezd+ZXl" +
            "zbp66669fLQ/3JxngdwxV8T8Nxxg61ShiaXLzQhy+ymo0nRS5ZRfJ+7tB8rS5VooybkfIddR8GvhnF8X/HEvgp/FTaRIujTX" +
            "tvONMS5WWRJ7ePy33XEPlqVmdty+Y25FXZhmdOXr1L9jf4yaF8DPjYPGnifUGsrE6RsN7b3d5bXMU8V/Y3kHkTWkEzxOZLVd" +
            "xMbIY1kX5WZGX/ou8b8w4qyvwvzDFcNuaxsPZOn7OLnP+NTUrRUZX9zmvo9Lt6H+EuWwoTxsI1rcut77bM9x8V+GNd+HHxK1" +
            "v4WeIfEWmazPo/lOmsaSiRR3EchcAPAtxO1vIpjbMbvuKmOQALIuYKz7G91LUPFPiDUb1LaVdS1m51Q31nb3MMck11PLNJGE" +
            "ukWXKFhl23F92Sxbca0K/wCcXPoYWnnFaOHiowUtEmpK3dNX33t0vbof7/8AhNis8xvhxllfOas6uKlSTqSqU3SnzXd4yhJR" +
            "d4fBzNL2nLzrSR//2Q==",
        ),
        // Orientation 8
        Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/4QAiRXhpZgAATU0AKgAAAAgAAQESAAMAAAABAAgAAAAAAAD/2wBDAAIBAQEBAQIBAQEC" +
            "AgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYH" +
            "CgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCABAACADASIAAhEBAxEB/8QA" +
            "HwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKB" +
            "kaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
            "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QA" +
            "HwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEI" +
            "FEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
            "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oA" +
            "DAMBAAIRAxEAPwD8c69S/Y3+DehfHP42DwX4n09r2xGkbzZW9peXNzLPLf2NnB5ENpPC8riS6XcDIqCNpG+ZlRW8trqPg18T" +
            "IvhB44l8av4VbV5G0aayt4BqaWyxSPPbyeY+63m8xQsLrtXy23Orb8KyP/tL435fxVmnhfmGF4bU3jZ+yVP2cnCf8am5WkpR" +
            "t7nNfVaXT0PznLZ0IY2Eq1uXW99tmfQljZalp/inxBp169tEum6zc6WbGzuLmaOOa1nlhkkD3TtLlyoyjbSm3BUNuFaFT+K/" +
            "E+u/Ef4la38U/EPh3TNGn1jykTR9JdJY7eOMuQXnW3ga4kYyNmR03BRHGCVjXEFf84ufTwtTOK0sPJSg5aNJRVuyStttfra/" +
            "U/3/APCbC55gvDjLKGc0p0sVGklUjUqOrPmu7ylOTk7z+Plbfs+bkWkTxL9mb9mfU/2kb/UrPT9Z1u2Nhq+jafs0DwVca3Kv" +
            "29rsG6nSCVGt7aH7KN8pDczKAM8HzjWYNItNbvLPw9rEuo6fDcFLLUZrNLdrlAB8/lpLKqjduAxIwIAbPOB7v/wT+m8X3Piz" +
            "XNA8HWLtJdaroJuZLP4gw6DNcrvvlWwuA1jePe6bOWb7TCI1jzDBvZi0amj8F/2HZ/iBaa9qHxW/aW+GHwosdL8YS+EdGvfF" +
            "dxqDWmt69GiSS2lr9ltpnhtYUmtjLdThI4luoN2Sxx/uPl/jU+GvGvP8JxTm0o4HD2VGioKabk4bKnTlUbine3NdLmctmj/B" +
            "OeW+2y6lKhT957u/+bsel0V1/wASf2Y/jJ8N9Qs/D3jLxV8OrjVLDxjoekfETwp4O+In2vWvBy3mp21nIuoA2RhiAeYWzzWr" +
            "XggnmjR1DEA8l438M6X4a8daRaeHPAnjTwwlz4ba/wBTsvHzXdvqdyz3lxBEZdOnkkSwCi2co0MjrOrhm2srKv8AiZW4PzTC" +
            "ZZVxmKapqHRu7ey05brd233Wttz/AGxy36RnAnEPG+B4byKNTFzxN/3kY8kIWU5PmVXkm2ow59I2cGnFyfun6Af8FhNfvPDf" +
            "/BQbSrHQPEmqnUtb8BeFi2jaDp2n3Fzcw2+p+IAINmo3loged7krHc2rveQGF1UwCcNJ0P7Of/BOfXtc/Zj8B+OvhF4e8PeO" +
            "vDvxD+H1rf8AiWxvviNqXht5rvU9M06DVxPcR6ddvqVjqMem6abmJ47e4MtmZFlBndF9F/4KI/8ABOP9oT9pD9tLwz+038Kt" +
            "F8C6rYaH4V0mwjsvFFzYJPHdWeo390xDX2hap5MbrdRASW3ky5jYv5myHZ6VB+0J8GP+CVv7OPwd/Zx/aB8WTXmsx+DF06zn" +
            "0q2URXjadDaRXUokuJIwBvuYcB382TeWw22Qj+X6XihmmF8L+Fsn4LrrFZnFVIVcKqarXilKVp0+W3ucqlGzT05nezZ/li8F" +
            "CWNr1MSuWGlpXt+J8HfHb4afE74s2Or65qnwN+HS6r4j8RWeq/EeTRtZ1W6j8XQjWrXV7+ytdO1Cf7Jo63l3axXUwSRxPPBG" +
            "C8KMxGV+xh8EPFfwov8AVdT1bwcugWt3otnaS2w8O6bpBvryK6vZGujZ6bd3UEeIZ7eHzPN3yeTyihA8nsXgHx3o/wARNCk1" +
            "3Rra7txb6ld6feWl/B5U1tdWtxJbzxOuSAyyxuvBIOMgkVtV9JxD4ocaZjlGJyDNYwtKXv3pqE4yjJSkrRsk3NNyvFyu5aq5" +
            "/kbxV4zeIWbZFjOGM6hTtOf7y9JQqQlCalKNo8sU3Ui3Nyg5czlqrn6YV+Vn/BYrxD8YNI/aqa38d6npk+gN4RuH+GPhnxVo" +
            "OuapoWpnzNKW8lltdDtpZ2niL3qySStHsFxpyxkpLcq36p18M/8ABYb9ir4t/tf+OPhU/gj4GJ440Tw9p3iFdUgOv/2b9jvb" +
            "iTSjayPNFqNjcLGY7e8B8p5lL+WJIeUmh/nL6N/EOT8N+LOExGayowoShVjKpW5VGn+7lJTjKWkZuUVBN7qbitZH+wecUqlb" +
            "ASULt6aLrr/TPjr9hbw94R0Oz8b3HhzxBqLPfeMNQnTRL+ylsxZWLX92bOZLae3gljE0J3BpI1YqiKVUx7V97ryr9mDwH4m8" +
            "LS+NPFPijwHNoT+J/FtxqVpDq81vNqxhkO7Zdy2000bhJGkEWJXYRld7Fy5Pqtft/iNi8NjuOMfiMPVVSEqjakmmmtNnHRpb" +
            "LfRK7buz/FXxZx2EzLxIzTFYWsq1OdWTjNOMoyWnwyjo4raLV9Ertu7f/9k=",
        ),
    )

    val HEIC: ByteArray get() = Base64.decode(
        "AAAAJGZ0eXBoZWljAAAAAG1pZjFNaVBybWlhZk1pSEJoZWljAAABhm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAHBpY3QAAAAA" +
        "AAAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAADnBpdG0AAAAAAAEAAAAjaWluZgAAAAAA" +
        "AQAAABVpbmZlAgAAAAABAABodmMxAAAAAOZpcHJwAAAAxWlwY28AAAATY29scm5jbHgAAgACAAaAAAAADGNsbGkAywBAAAAA" +
        "FGlzcGUAAAAAAAAAQAAAACAAAAAJaXJvdAAAAAAQcGl4aQAAAAADCAgIAAAAcWh2Y0MBA3AAAACwAAAAAAAe8AD8/fj4AAAL" +
        "A6AAAQAXQAEMAf//A3AAAAMAsAAAAwAAAwAecCShAAEAI0IBAQNwAAADALAAAAMAAAMAHqAUIEHBjCOIe5FlU3AgIGAIogAB" +
        "AAlEAcBhcshAUyQAAAAZaXBtYQAAAAAAAAABAAEGgQIDBYaEAAAAHmlsb2MAAAAARAAAAQABAAAAAQAAAboAAABxAAAAAW1k" +
        "YXQAAAAAAAAAgQAAAG0oAa+jwYAqtP/84T//Q3rLyFO/hzZv/hZVp9t/4/4sA2VAWz/4jP/4Q9/pbB0QEv/Faj+jfhbk052Q" +
        "9cn/yciD7vi/kASuj6BEXDZ/+Ve6G86OKDv2CRP9L9vJ+VZLCkPhCfo/8Ryu1vothz+A",
    )
}
