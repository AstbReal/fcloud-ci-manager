package ci.fcloud.manager.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用创建时的字段搬运：入参里每个可落库字段都必须被复制。
 *
 * 回归用例 —— importFromGitLab 曾经因为 create() 只 copy 了 code/name/description/sortOrder，
 * 导致 gitlabProjectId / repoUrl 从未写入数据库。
 */
class ApplicationServiceMappingTest {

    @Test
    void fromBodyCopiesEveryPersistableField() {
        Application body = new Application();
        body.setCode("payment-service");
        body.setName("支付服务");
        body.setDescription("结算链路");
        body.setSortOrder(7);
        body.setGitlabProjectId(987654321L);
        body.setRepoUrl("https://gitlab.example.com/group/payment-service");

        Application a = ApplicationService.fromBody(body);

        assertEquals("payment-service", a.getCode());
        assertEquals("支付服务", a.getName());
        assertEquals("结算链路", a.getDescription());
        assertEquals(7, a.getSortOrder());
        assertEquals(987654321L, a.getGitlabProjectId(), "GitLab 项目 id 必须落库");
        assertEquals("https://gitlab.example.com/group/payment-service", a.getRepoUrl(),
                "仓库地址必须落库");
    }

    @Test
    void sortOrderDefaultsToZeroAndGitlabFieldsStayNullForManualCreate() {
        Application body = new Application();
        body.setCode("manual");
        body.setName("手工应用");

        Application a = ApplicationService.fromBody(body);

        assertEquals(0, a.getSortOrder());
        assertNull(a.getGitlabProjectId());
        assertNull(a.getRepoUrl());
    }
}
