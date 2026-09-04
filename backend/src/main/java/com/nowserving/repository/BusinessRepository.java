package com.nowserving.repository;

import com.nowserving.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA magic: declare an interface, get a full CRUD implementation
 * (save, findById, delete, ...) generated at startup. The two type parameters
 * are &lt;entity, id type&gt;.
 */
public interface BusinessRepository extends JpaRepository<Business, Long> {

    /** Resolve a scanned restaurant QR. */
    java.util.Optional<Business> findByPublicToken(String publicToken);
}
