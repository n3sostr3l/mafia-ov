package ru.polemica.studio;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
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
  Map<String, String> upload(Authentication auth, @RequestParam MultipartFile file)
      throws IOException {
    if (file.isEmpty()
        || file.getSize() > 5 * 1024 * 1024
        || !Set.of("image/png", "image/jpeg").contains(Objects.toString(file.getContentType(), "")))
      throw ApiErrors.bad("Выберите JPG или PNG до 5 МБ.");
    BufferedImage image;
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw ApiErrors.bad("Файл не является изображением JPG/PNG.");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        if (!Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
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
    record.ownerId = auth.getName();
    record.createdAt = Instant.now();
    ImageIO.write(clean, "png", root.resolve(record.id + ".png").toFile());
    uploads.save(record);
    return Map.of("id", record.id);
  }

  @GetMapping("/uploads/{id}")
  ResponseEntity<byte[]> image(Authentication auth, @PathVariable String id) throws IOException {
    uploads
        .findById(id)
        .filter(u -> u.ownerId.equals(auth.getName()))
        .orElseThrow(ApiErrors::missing);
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
