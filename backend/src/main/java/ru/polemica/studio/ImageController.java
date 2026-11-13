package ru.polemica.studio;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.*;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class ImageController {
  private final Path root;
  private final UploadRepository uploads;
  private final GameService games;

  ImageController(@Value("${app.uploads}") String path, UploadRepository uploads, GameService games)
      throws IOException {
    this.root = Path.of(path).toAbsolutePath().normalize();
    Files.createDirectories(root);
    this.uploads = uploads;
    this.games = games;
  }

  @PostMapping("/uploads")
  @Transactional
  Map<String, String> upload(@RequestParam MultipartFile file) throws IOException {
    if (file.isEmpty()
        || file.getSize() > 5 * 1024 * 1024
        || !Set.of("image/png", "image/jpeg").contains(Objects.toString(file.getContentType(), "")))
      throw ApiErrors.bad("Выберите JPG или PNG до 5 МБ.");
    byte[] bytes = file.getBytes();
    BufferedImage image;
    String format;
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw ApiErrors.bad("Файл не является изображением JPG/PNG.");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("png", "jpeg", "jpg").contains(format))
          throw ApiErrors.bad("Разрешены только JPG и PNG.");
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 16000000)
          throw ApiErrors.bad("Размер изображения: до 4096 × 4096 и 16 мегапикселей.");
        image = reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw ApiErrors.bad("Не удалось прочитать изображение.");
    }
    if (format.equals("jpeg") || format.equals("jpg"))
      image = applyExifOrientation(image, exifOrientation(bytes));
    double scale = Math.min(1, 1024.0 / Math.max(image.getWidth(), image.getHeight()));
    var clean =
        new BufferedImage(
            Math.max(1, (int) (image.getWidth() * scale)),
            Math.max(1, (int) (image.getHeight() * scale)),
            BufferedImage.TYPE_INT_ARGB);
    var graphics = clean.createGraphics();
    graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.drawImage(image, 0, 0, clean.getWidth(), clean.getHeight(), null);
    graphics.dispose();
    var record = new Upload();
    record.id = UUID.randomUUID().toString();
    record.createdAt = Instant.now();
    ImageIO.write(clean, "png", root.resolve(record.id + ".png").toFile());
    uploads.save(record);
    return Map.of("id", record.id);
  }

  private static int exifOrientation(byte[] bytes) {
    try (var stream = new ByteArrayInputStream(bytes)) {
      var metadata = ImageMetadataReader.readMetadata(stream);
      var exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      return exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)
          ? exif.getInt(ExifIFD0Directory.TAG_ORIENTATION)
          : 1;
    } catch (ImageProcessingException | IOException | MetadataException e) {
      throw ApiErrors.bad("Не удалось прочитать ориентацию JPG.");
    }
  }

  private static BufferedImage applyExifOrientation(BufferedImage image, int orientation) {
    if (orientation < 2 || orientation > 8) return image;

    int width = image.getWidth();
    int height = image.getHeight();
    boolean swapDimensions = orientation >= 5;
    var oriented =
        new BufferedImage(
            swapDimensions ? height : width,
            swapDimensions ? width : height,
            BufferedImage.TYPE_INT_ARGB);
    var transform = new AffineTransform();
    switch (orientation) {
      case 2 -> {
        transform.translate(width, 0);
        transform.scale(-1, 1);
      }
      case 3 -> {
        transform.translate(width, height);
        transform.rotate(Math.PI);
      }
      case 4 -> {
        transform.translate(0, height);
        transform.scale(1, -1);
      }
      case 5 -> transform.setTransform(0, 1, 1, 0, 0, 0);
      case 6 -> {
        transform.translate(height, 0);
        transform.rotate(Math.PI / 2);
      }
      case 7 -> transform.setTransform(0, -1, -1, 0, height, width);
      case 8 -> {
        transform.translate(0, width);
        transform.rotate(-Math.PI / 2);
      }
      default -> {
        return image;
      }
    }
    var graphics = oriented.createGraphics();
    graphics.drawImage(image, transform, null);
    graphics.dispose();
    return oriented;
  }

  @GetMapping("/uploads/{id}")
  ResponseEntity<byte[]> image(@PathVariable String id) throws IOException {
    uploads.findById(id).orElseThrow(ApiErrors::missing);
    return response(id);
  }

  @GetMapping("/public/games/{gameId}/images/{id}")
  ResponseEntity<byte[]> publicImage(
      @PathVariable String gameId, @PathVariable String id, @RequestParam String token)
      throws IOException {
    var g = games.publicGame(gameId, token);
    var projected = games.view(g, true);
    if (projected.state().players.stream().noneMatch(p -> id.equals(p.photo)))
      throw ApiErrors.missing();
    return response(id);
  }

  private ResponseEntity<byte[]> response(String id) throws IOException {
    if (!id.matches("[a-f0-9-]{36}")) throw ApiErrors.missing();
    var path = root.resolve(id + ".png");
    if (!Files.isRegularFile(path)) throw ApiErrors.missing();
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .body(Files.readAllBytes(path));
  }
}
