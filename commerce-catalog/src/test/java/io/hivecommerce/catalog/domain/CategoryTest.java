package io.hivecommerce.catalog.domain;

import static io.hivecommerce.catalog.domain.CatalogFixtures.NOW;
import static io.hivecommerce.catalog.domain.CatalogFixtures.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.Ids;
import io.hivecommerce.shared.LocalizedText;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryTest {
  private static Category create(Category parent, String slug, int maxDepth) {
    return Category.create(Ids.newId(), SCOPE, parent, slug, LocalizedText.of("en", slug), List.of(), 0, maxDepth, NOW);
  }

  @Test
  void buildsPathsAndRespectsMaximumDepth() {
    var root = create(null, "crafts", 3);
    var pottery = create(root, "pottery", 3);
    var bowls = create(pottery, "bowls", 3);
    assertThat(bowls.depth()).isEqualTo(3);
    assertThat(bowls.path()).isEqualTo("/" + root.id() + "/" + pottery.id() + "/" + bowls.id() + "/");
    assertThatThrownBy(() -> create(bowls, "tiny", 3)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("CATEGORY_TOO_DEEP"));
  }

  @Test
  void rejectsCyclesAndTooDeepMoves() {
    var root = create(null, "a", 4);
    var child = create(root, "b", 4);
    var grandchild = create(child, "c", 4);
    assertThatThrownBy(() -> root.moveTo(grandchild, 2, 4, NOW)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("CATEGORY_CYCLE"));
    var other = create(null, "d", 4);
    var deep = create(create(create(other, "e", 4), "f", 4), "g", 4);
    assertThatThrownBy(() -> child.moveTo(deep, 1, 4, NOW)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("CATEGORY_TOO_DEEP"));
    String old = grandchild.moveTo(null, 0, 4, NOW);
    assertThat(old).contains(root.id().toString());
    assertThat(grandchild.depth()).isEqualTo(1);
  }

  @Test
  void archivesOnlyWithoutActiveChildrenAndRejectsArchivedParents() {
    var root = create(null, "a", 5);
    assertThatThrownBy(() -> root.archive(true, NOW)).isInstanceOf(DomainRuleException.class);
    root.archive(false, NOW);
    assertThat(root.status()).isEqualTo(Category.Status.ARCHIVED);
    assertThatThrownBy(() -> create(root, "b", 5)).isInstanceOfSatisfying(DomainRuleException.class, e -> assertThat(e.code()).isEqualTo("CATEGORY_PARENT_ARCHIVED"));
  }

  @Test
  void validatesSlugAndAttributeKeys() {
    assertThatThrownBy(() -> Category.create(Ids.newId(), SCOPE, null, "Bad Slug", LocalizedText.of("en", "x"), List.of("ok", "ok", "Bad"), 0, 5, NOW))
        .isInstanceOfSatisfying(DomainValidationException.class, e -> assertThat(e.violations()).hasSize(3));
  }
}
