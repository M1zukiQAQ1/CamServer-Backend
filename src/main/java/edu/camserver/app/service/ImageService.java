package edu.camserver.app.service;

import com.querydsl.core.BooleanBuilder;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.QImage;
import edu.camserver.app.repository.ImageRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImageService {
    private final ImageRepository imageRepository;
    public ImageService(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @Transactional
    public Image save(Image image) {
        return imageRepository.save(image);
    }

    public List<Image> findAll(int pageSize, String lastUID, String conditions) {
        QImage image = QImage.image;
        BooleanBuilder builder = new BooleanBuilder();

        if (lastUID != null) {
            builder.and(image.imgId.lt(Integer.parseInt(lastUID)));
        }

        Pageable pageable = PageRequest.of(
                0,
                pageSize,
                Sort.by("imgId").descending()
        );

        if (!builder.hasValue()) {
            return imageRepository.findAll(pageable).getContent();
        }

        return imageRepository.findAll(builder, pageable).getContent();
    }
}
