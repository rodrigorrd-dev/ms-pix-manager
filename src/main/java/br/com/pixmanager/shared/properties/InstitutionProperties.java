package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Identidade da instituição (decisão A3): o ISPB NUNCA é literal no código — vem daqui e vai nos eventos. */
@ConfigurationProperties(prefix = "pix.institution")
public record InstitutionProperties(String ispb, String name) {

    public InstitutionProperties {
        if (ispb == null || !ispb.matches("\\d{8}")) {
            throw new IllegalStateException("pix.institution.ispb deve ter 8 dígitos (ISPB da instituição)");
        }
    }
}
