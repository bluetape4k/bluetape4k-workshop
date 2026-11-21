package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * [ImageAssetTable] 행의 생명주기 상태입니다. API가 단순히 파일 존재 여부가
 * 아니라 처리 결과를 표현할 수 있도록 asset 수준에서 유지합니다.
 *
 * ## 상태
 * - [PROCESSING] — asset이 현재 처리 중입니다. 아직 public variant를
 *   성공으로 간주하면 안 됩니다.
 * - [READY] — 처리가 성공적으로 끝나 파생 객체를 사용할 수 있습니다. 같은
 *   checksum 업로드는 이 상태에서 idempotent하게 기존 결과를 재사용합니다.
 * - [FAILED] — 처리 또는 storage가 실패했으며 job history와 cleanup 결과를
 *   확인한 뒤 재시도할 수 있습니다.
 */
enum class ImageAssetStatus {
    /** 검증·변환·업로드가 진행 중이며 public variant를 성공으로 볼 수 없습니다. */
    /** 검증·변환·업로드가 진행 중이며 public variant를 성공으로 볼 수 없습니다. */
    PROCESSING,
    /** 필요한 파생 객체가 준비되어 동일 checksum 요청을 멱등 재사용할 수 있습니다. */
    /** 필요한 파생 객체가 준비되어 동일 checksum 요청을 멱등 재사용할 수 있습니다. */
    READY,
    /** 처리 또는 storage가 실패해 job history 확인 후 재시도 판단이 필요합니다. */
    /** 처리 또는 storage가 실패해 job history 확인 후 재시도 판단이 필요합니다. */
    FAILED,
}
