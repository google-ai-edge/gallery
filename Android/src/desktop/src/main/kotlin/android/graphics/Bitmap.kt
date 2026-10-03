package android.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO

class Bitmap(val bufferedImage: BufferedImage) {
  val width: Int get() = bufferedImage.width
  val height: Int get() = bufferedImage.height
  val isRecycled: Boolean get() = false

  enum class CompressFormat {
    JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS
  }

  enum class Config {
    ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE
  }

  fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
    bufferedImage.getRGB(x, y, width, height, pixels, offset, stride)
  }

  fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
    val formatName = when (format) {
      CompressFormat.PNG -> "png"
      CompressFormat.JPEG -> "jpeg"
      else -> "png"
    }
    return ImageIO.write(bufferedImage, formatName, stream)
  }

  fun copy(config: Config, isMutable: Boolean): Bitmap {
    val copy = BufferedImage(width, height, bufferedImage.type)
    val g = copy.createGraphics()
    g.drawImage(bufferedImage, 0, 0, null)
    g.dispose()
    return Bitmap(copy)
  }

  fun recycle() {}

  companion object {
    @JvmStatic
    fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
      val imageType = if (config == Config.RGB_565) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
      val img = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), imageType)
      return Bitmap(img)
    }

    @JvmStatic
    fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap {
      val img = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
      img.setRGB(0, 0, width, height, colors, 0, width)
      return Bitmap(img)
    }

    @JvmStatic
    fun createBitmap(colors: IntArray, offset: Int, stride: Int, width: Int, height: Int, config: Config): Bitmap {
      val img = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
      img.setRGB(0, 0, width, height, colors, offset, stride)
      return Bitmap(img)
    }

    @JvmStatic
    fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int, m: Matrix? = null, filter: Boolean = false): Bitmap {
      val sub = source.bufferedImage.getSubimage(x, y, width, height)
      val copy = BufferedImage(width, height, sub.type)
      val g = copy.createGraphics()
      if (m != null && m.postRotateDeg != 0f) {
        g.rotate(Math.toRadians(m.postRotateDeg.toDouble()), width / 2.0, height / 2.0)
      }
      g.drawImage(sub, 0, 0, null)
      g.dispose()
      return Bitmap(copy)
    }

    @JvmStatic
    fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap {
      val scaledImg = BufferedImage(dstWidth.coerceAtLeast(1), dstHeight.coerceAtLeast(1), src.bufferedImage.type)
      val g = scaledImg.createGraphics()
      val hints = if (filter) java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR else java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
      g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, hints)
      g.drawImage(src.bufferedImage, 0, 0, dstWidth, dstHeight, null)
      g.dispose()
      return Bitmap(scaledImg)
    }
  }
}

fun Bitmap.asImageBitmap(): ImageBitmap {
  return bufferedImage.toComposeImageBitmap()
}

fun Bitmap.scale(dstWidth: Int, dstHeight: Int, filter: Boolean = true): Bitmap =
  Bitmap.createScaledBitmap(this, dstWidth, dstHeight, filter)

object BitmapFactory {
  class Options {
    var inJustDecodeBounds: Boolean = false
    var inSampleSize: Int = 1
    var outWidth: Int = 0
    var outHeight: Int = 0
    var inPreferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888
  }

  @JvmStatic
  fun decodeFile(pathName: String, opts: Options? = null): Bitmap? {
    return runCatching {
      val file = File(pathName)
      if (!file.exists()) return null
      val img = ImageIO.read(file) ?: return null
      if (opts != null) {
        opts.outWidth = img.width
        opts.outHeight = img.height
        if (opts.inJustDecodeBounds) return null
      }
      Bitmap(img)
    }.getOrNull()
  }

  @JvmStatic
  fun decodeStream(stream: InputStream, outPadding: Any? = null, opts: Options? = null): Bitmap? {
    return runCatching {
      val img = ImageIO.read(stream) ?: return null
      if (opts != null) {
        opts.outWidth = img.width
        opts.outHeight = img.height
        if (opts.inJustDecodeBounds) return null
      }
      Bitmap(img)
    }.getOrNull()
  }

  @JvmStatic
  fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? {
    return decodeStream(ByteArrayInputStream(data, offset, length), null, opts)
  }
}
