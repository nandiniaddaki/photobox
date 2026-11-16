package com.nandini.photoapp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Web endpoints: the page, upload, delete, and serving images. */
@Controller
public class PhotoController {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String MY_PHOTOS = "myPhotos";

    private final PhotoService service;
    private final ActivityLog log;

    public PhotoController(PhotoService service, ActivityLog log) {
        this.service = service;
        this.log = log;
    }

    @GetMapping("/")
    public String home(@RequestParam(value = "error", required = false) String error, Model model, HttpSession session) {
        PhotoService.FeedResult feed = service.getFeed();
        model.addAttribute("feed", feed);
        model.addAttribute("shardCount", ShardRouter.SHARD_COUNT);
        model.addAttribute("events", log.recent());
        model.addAttribute("mine", myPhotos(session));
        if ("toobig".equals(error)) {
            model.addAttribute("error", "Photo is too big. Max size is 2 MB.");
        }
        return "index";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam(value = "owner", required = false) String owner,
                         @RequestParam(value = "file", required = false) MultipartFile file,
                         HttpServletRequest request, HttpSession session, RedirectAttributes ra) {
        try {
            String id = service.upload(owner, file, clientIp(request));
            myPhotos(session).add(id);
            ra.addFlashAttribute("success", "Photo uploaded! Notice: the cache was invalidated, so this page load is a MISS.");
        } catch (PhotoService.UploadRejectedException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/photos/{id}/delete")
    public String delete(@PathVariable("id") String id, HttpSession session, RedirectAttributes ra) {
        Set<String> mine = myPhotos(session);
        if (!UUID_PATTERN.matcher(id).matches() || !mine.contains(id)) {
            ra.addFlashAttribute("error", "You can only delete photos you uploaded.");
            return "redirect:/";
        }
        if (service.delete(id)) {
            mine.remove(id);
            ra.addFlashAttribute("success", "Photo deleted (and cache invalidated).");
        }
        return "redirect:/";
    }

    /** Images never change once uploaded, so the BROWSER can cache them for a year (another cache layer!). */
    @GetMapping("/photos/{id}")
    public ResponseEntity<byte[]> image(@PathVariable("id") String id) {
        if (!UUID_PATTERN.matcher(id).matches()) {
            return ResponseEntity.notFound().build();
        }
        return service.findImage(id)
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(img.contentType()))
                        .header("Cache-Control", "public, max-age=31536000, immutable")
                        .header("X-Content-Type-Options", "nosniff")
                        .body(img.data()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Render pings this to know the app is alive. */
    @GetMapping("/health")
    @ResponseBody
    public String health() {
        return "OK";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooBig() {
        return "redirect:/?error=toobig";
    }

    @SuppressWarnings("unchecked")
    private Set<String> myPhotos(HttpSession session) {
        Object existing = session.getAttribute(MY_PHOTOS);
        if (existing instanceof Set<?> set) {
            return (Set<String>) set;
        }
        Set<String> created = new HashSet<>();
        session.setAttribute(MY_PHOTOS, created);
        return created;
    }

    /** Render sits behind a proxy, so the real user IP is in X-Forwarded-For. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
