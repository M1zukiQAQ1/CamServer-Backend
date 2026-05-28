package edu.camserver.app.service;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.ImageFilter;
import edu.camserver.app.model.QImage;
import edu.camserver.app.repository.ImageRepository;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImageService {
    private final ImageRepository imageRepository;
    private final Logger log =  LoggerFactory.getLogger(ImageService.class);

    public ImageService(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @Transactional
    public Image save(Image image) {
        return imageRepository.save(image);
    }

    @Modifying
    public Image setFeatured(long imgId, boolean featured) {
        Image image = imageRepository.findById(imgId).orElseThrow(() -> new NoResultException("Image not found"));
        image.setFeatured(featured);
        imageRepository.save(image);
        return image;
    }

    public Image findById(long imgId) {
        return imageRepository.findById(imgId).orElseThrow(() -> new NoResultException("Image not found"));
    }

    public List<Image> findAll(int pageSize, String lastUID, ImageFilter filter) {

        QImage image = QImage.image;
        BooleanBuilder builder = new BooleanBuilder();
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));

        if (hasText(lastUID)) {
            builder.and(image.imgId.lt(Long.parseLong(lastUID)));
        }

        if (filter.getFeatured() != null) {
            builder.and(image.featured.eq(filter.getFeatured()));
        }

        if (filter.getStartDate() != null && filter.getEndDate() != null) {
            builder.and(image.timestamp.between(filter.getStartDate(), filter.getEndDate()));
        } else if (filter.getStartDate() != null) {
            builder.and(image.timestamp.goe(filter.getStartDate()));
        } else if (filter.getEndDate() != null) {
            builder.and(image.timestamp.loe(filter.getEndDate()));
        }

        if (hasText(filter.getSiteName())) {
            builder.and(image.siteName.eq(filter.getSiteName()));
        }

        if (hasText(filter.getSearch())) {
            String search = filter.getSearch().trim();
            builder.and(
                    image.siteName.containsIgnoreCase(search)
                            .or(image.cameraId.containsIgnoreCase(search))
                            .or(image.imgPath.containsIgnoreCase(search))
                            .or(image.timeZone.containsIgnoreCase(search))
            );
        }

        if (hasText(filter.getPeriod())) {
            addPeriodFilter(builder, image, filter.getPeriod());
        }

        Pageable pageable = PageRequest.of(
                0,
                normalizedPageSize,
                Sort.by("imgId").descending()
        );

        if (!builder.hasValue()) {
            return imageRepository.findAll(pageable).getContent();
        }

        return imageRepository.findAll(builder, pageable).getContent();
    }

    private void addPeriodFilter(BooleanBuilder builder, QImage image, String period) {
        NumberExpression<Integer> hour = image.timestamp.hour();

        switch (period.trim().toLowerCase()) {
            case "dawn" -> builder.and(hour.goe(5).and(hour.lt(7)));
            case "day" -> builder.and(hour.goe(7).and(hour.lt(18)));
            case "dusk" -> builder.and(hour.goe(18).and(hour.lt(20)));
            case "night" -> builder.and(hour.goe(20).or(hour.lt(5)));
            default -> {
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
