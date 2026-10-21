<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div class="head-main">
        <h1 class="fc-page-title">
          <span>{{ module?.name || '模块' }}</span>
          <span v-if="module?.isShared" class="tag tag-shared">共享</span>
          <span class="path-inline mono">{{ pathText }}</span>
        </h1>
        <div class="page-meta">
          <span class="num">{{ rows.length }} 个变量</span>
          <span class="sep">·</span>
          <span class="num">{{ envs.length }} 个环境</span>
          <template v-if="module?.updatedAt">
            <span class="sep">·</span>
            <span>最后更新 {{ fmt(module.updatedAt) }}</span>
          </template>
        </div>
      </div>

      <div class="header-actions ph-actions">
        <button class="btn" @click="editOpen = true">
          <ProtoIcon name="sliders" :size="15" />编辑
        </button>
        <button class="btn" @click="importDrawer = true">
          <ProtoIcon name="upload" :size="15" />粘贴导入
        </button>
        <button class="btn" @click="copyDotenv">
          <ProtoIcon name="copy" :size="15" />复制 dotenv
        </button>
        <button class="btn" @click="copyLink()">
          <ProtoIcon name="arrow-r" :size="15" />复制深链
        </button>
        <button class="btn btn-primary" @click="openAddVariable">
          <ProtoIcon name="plus" :size="15" />变量
        </button>
        <el-dropdown trigger="click" @command="onModuleCommand">
          <button class="icon-btn"><ProtoIcon name="more" :size="15" /></button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-if="isAdmin"
                command="delete"
                style="color: var(--danger)"
              >
                删除模块
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <div v-if="deepLinkInfo" class="deep-bar">
      <el-icon :size="14"><Aim /></el-icon>
      <span>
        已从链接定位到
        <b class="mono">{{ deepLinkInfo.varKey }}</b>
        <template v-if="deepEnvLabel"> · {{ deepEnvLabel }}</template>
      </span>
      <button @click="clearDeepLink">清除定位</button>
    </div>

    <div v-if="templateDiff?.hasUpdate" class="tpl-bar">
      <span class="tpl-ic"><el-icon :size="15"><RefreshRight /></el-icon></span>
      <div class="tpl-main">
        <b>模板有更新</b>
        <span class="tpl-detail">
          新增 {{ templateDiff.added.length }} · 变更 {{ templateDiff.changed.length }} ·
          删除 {{ templateDiff.removed.length }}，同步不会覆盖已填的值
        </span>
      </div>
      <button class="tpl-btn" @click="openTemplateDiff">查看并同步 →</button>
    </div>

    <MatrixToolbar
      ref="toolbarRef"
      v-model="q"
      v-model:prefix="prefix"
      :rows="rows"
      :visible-count="filteredRows.length"
    />

    <div class="matrix-wrap">
      <table class="matrix">
        <thead>
          <tr>
            <th class="c-drag"></th>
            <th class="c-key">变量</th>
            <th class="c-type">类型</th>
            <th class="c-remark">备注</th>
            <th class="c-global">全局值</th>
            <th v-for="e in envs" :key="e.id" class="c-env">
              <span class="env-head">
                <i class="env-dot" :style="{ background: e.color || '#2563eb' }" />
                <span class="env-name">{{ e.name }}</span>
                <span v-if="e.isDefault" class="env-default">默认</span>
                <span v-if="missingCount(e.id) > 0" class="env-count" :title="`${missingCount(e.id)} 个必填变量缺失`">
                  {{ missingCount(e.id) }}
                </span>
              </span>
            </th>
            <th class="c-ops"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in filteredRows"
            :key="row.id"
            :data-row-id="row.id"
            :class="{
              'row-hit': hitRowId === row.id,
              dragging: dragRowId === row.id,
              'drag-over': overRowId === row.id
            }"
            @dragover.prevent="onDragOver(row)"
            @drop.prevent="onDrop(row)"
            @dragend="onDragEnd"
          >
            <td class="c-drag">
              <span
                class="grip"
                draggable="true"
                title="拖动调整顺序"
                @dragstart="onDragStart($event, row)"
              >⠿</span>
            </td>
            <td class="c-key">
              <div class="key-wrap">
                <span class="key mono">{{ row.varKey }}</span>
                <span v-if="row.isRequired" class="req" title="必填">*</span>
              </div>
            </td>
            <td class="c-type">
              <span class="tag" :class="row.valueType === 'ENCRYPTED' ? 'tag-lock' : 'tag-plain'">
                {{ row.valueType === 'ENCRYPTED' ? '加密' : '明文' }}
              </span>
            </td>
            <td class="c-remark">
              <span class="remark" :title="row.remark || ''">{{ row.remark || '—' }}</span>
            </td>

            <td
              class="c-global"
              :class="{ 'cell-hit': hitRowId === row.id && hitEnvId === 0 }"
              @click.capture="markCell(row, 0)"
            >
              <CellEditor
                :ref="(el) => setCellRef(row.id, 0, el)"
                :row="row"
                :cell="row.cells['0']"
                scope="GLOBAL"
                :environments="envs"
                @saved="loadMatrix"
              />
            </td>

            <td
              v-for="e in envs"
              :key="e.id"
              class="c-env"
              :class="{ 'cell-hit': hitRowId === row.id && hitEnvId === e.id }"
              @click.capture="markCell(row, e.id)"
            >
              <CellEditor
                :ref="(el) => setCellRef(row.id, e.id, el)"
                :row="row"
                :cell="row.cells[String(e.id)]"
                scope="ENV"
                :environment-id="e.id"
                :environments="envs"
                @saved="loadMatrix"
                @chain="(anchor) => openChain(row, e, anchor)"
              />
            </td>

            <td class="c-ops">
              <el-dropdown trigger="click" @command="(c: string) => onRowCommand(c, row)">
                <button class="row-more" title="变量操作">
                  <el-icon :size="14"><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑定义</el-dropdown-item>
                    <el-dropdown-item command="copylink">
                      复制此值链接 · {{ linkTargetLabel(row) }}
                    </el-dropdown-item>
                    <el-dropdown-item command="up" :disabled="row.id === rows[0]?.id">
                      上移
                    </el-dropdown-item>
                    <el-dropdown-item
                      command="down"
                      :disabled="row.id === rows[rows.length - 1]?.id"
                    >
                      下移
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="isAdmin"
                      command="delete"
                      divided
                      style="color: #dc2626"
                    >
                      删除
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </td>
          </tr>

          <tr v-if="!filteredRows.length" class="mx-empty">
            <td :colspan="envs.length + 6">
              <template v-if="loading">
                <div class="e-t">加载中…</div>
              </template>
              <template v-else-if="!rows.length">
                <div class="e-t">还没有变量</div>
                <div class="e-s">从模板实例化，或手动添加第一个变量后开始配置。</div>
                <button class="e-btn" @click="openAddVariable">添加第一个变量</button>
              </template>
              <template v-else>
                <div class="e-t">没有匹配的变量</div>
                <div class="e-s">
                  当前筛选：{{ q ? `“${q}”` : '' }}{{ q && prefix ? ' · ' : '' }}{{ prefix || '' }}
                </div>
                <button class="e-btn" @click="clearFilters">清除筛选</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 文件 / 成员：矩阵下方双栏，同屏可完成「改值 + 换文件」 -->
    <div class="panels">
      <section class="fc-card panel">
        <div class="panel-h">
          <div class="panel-t">
            文件
            <span class="n">{{ files.length }}</span>
          </div>
          <div class="panel-actions">
            <el-select
              v-model="fileEnvId"
              size="small"
              style="width: 104px"
              placeholder="环境"
              @change="loadFiles"
            >
              <el-option v-for="e in envs" :key="e.id" :label="e.name" :value="e.id" />
            </el-select>
          </div>
        </div>
        <div
          class="file-list"
          :class="{ 'drop-on': dragActive }"
          @dragenter.prevent="onDragEnter"
          @dragover.prevent
          @dragleave="onDragLeave"
          @drop.prevent="onDropFiles"
        >
          <input
            ref="fileInputRef"
            type="file"
            multiple
            hidden
            @change="onPickFiles"
          />

          <!-- 拖拽和点击共用同一个入口 -->
          <button
            type="button"
            class="drop-zone"
            :class="{ 'is-empty': !files.length }"
            :disabled="!fileEnvId"
            :title="`可多选 · 单文件 ≤ ${MAX_FILE_MB}MB，单模块合计 ≤ 500MB`"
            @click="openFilePicker"
          >
            <template v-if="fileEnvId">
              <template v-if="files.length">
                <el-icon :size="14"><Upload /></el-icon>
                <span>拖拽文件到这里，或点击选择（可多选）</span>
              </template>
              <template v-else>
                <el-icon :size="18"><Upload /></el-icon>
                <span class="dz-t">拖拽文件到这里，或点击选择</span>
                <span class="dz-s">支持一次多选 · 单文件 ≤ {{ MAX_FILE_MB }}MB</span>
              </template>
            </template>
            <span v-else>先给模块绑定环境</span>
          </button>

          <div v-for="f in files" :key="f.id" class="file-row">
            <span class="file-ic"><el-icon :size="13"><Document /></el-icon></span>
            <div class="file-main" title="点击预览" @click="openPreview(f)">
              <div class="file-key mono" :title="f.fileKey">{{ f.fileKey }}</div>
              <div class="file-sub">{{ f.remark || '无备注' }} · v{{ f.currentVersion }}</div>
            </div>
            <div class="file-ops">
              <button @click="openPreview(f)">预览</button>
              <button @click="downloadFile(f)">下载</button>
              <button v-if="isAdmin" class="danger" @click="deleteFile(f)">删除</button>
            </div>
          </div>
          <div v-if="dragActive" class="drop-layer">
            <div class="drop-box">
              <el-icon :size="18"><Upload /></el-icon>
              <div class="drop-t">松手，上传到 <b>{{ fileEnvName }}</b></div>
              <div class="drop-s">可一次拖入多个文件 · 单文件 ≤ {{ MAX_FILE_MB }}MB</div>
            </div>
          </div>
        </div>
        <div class="panel-foot">
          可拖拽或多选 · 单文件 ≤ {{ MAX_FILE_MB }}MB · 同名上传生成新版本，保留最近 3 个
        </div>
      </section>

    </div>

    <!-- 继承链 popover -->
    <InheritChain
      :open="chainOpen"
      :anchor="chainAnchor"
      :data="chainData"
      :loading="chainLoading"
      :var-key="chainRow?.varKey || ''"
      :env-name="chainEnv?.name || ''"
      @close="closeChain"
      @override="onChainOverride"
    />

    <!-- 变量新增 / 编辑 -->
    <el-drawer
      v-model="varDrawer"
      :title="varForm.id ? '编辑变量' : '新增变量'"
      size="480px"
    >
      <div class="var-form">
        <label class="fld">
          <span class="fld-lb">
            Key<span class="req">*</span>
            <span class="fld-opt">
              {{ varForm.id ? '创建后不可修改' : '大写字母、数字、下划线' }}
            </span>
          </span>
          <el-input
            v-model="varForm.varKey"
            :disabled="!!varForm.id"
            class="k-input"
            :class="{ bad: !!keyError }"
            placeholder="DB_HOST"
            @blur="varForm.varKey = varForm.varKey.trim()"
            @keyup.enter="saveVariable"
          />
          <span v-if="keyError" class="err-text">{{ keyError }}</span>
        </label>

        <div class="fld">
          <span class="fld-lb">类型</span>
          <div class="seg">
            <button
              type="button"
              :class="{ on: varForm.valueType === 'PLAIN' }"
              @click="varForm.valueType = 'PLAIN'"
            >
              明文
            </button>
            <button
              type="button"
              :class="{ on: varForm.valueType === 'ENCRYPTED' }"
              @click="varForm.valueType = 'ENCRYPTED'"
            >
              加密
            </button>
          </div>
          <span class="fld-hint">
            {{
              varForm.valueType === 'ENCRYPTED'
                ? '值以主密钥加密存储，矩阵里显示为圆点，每次查看明文都会记一条高危审计。'
                : '明文存储，矩阵里直接可读可改。'
            }}
          </span>
        </div>

        <label v-if="!varForm.id" class="fld">
          <span class="fld-lb">全局值<span class="fld-opt">可选</span></span>
          <el-input
            v-model="varForm.globalValue"
            :type="varForm.valueType === 'ENCRYPTED' ? 'password' : 'text'"
            :show-password="varForm.valueType === 'ENCRYPTED'"
            :placeholder="
              varForm.valueType === 'ENCRYPTED'
                ? '可留空，由各环境单独填'
                : '所有环境默认继承这个值'
            "
            @keyup.enter="saveVariable"
          />
          <span class="fld-hint">
            留空则只建定义，各环境自己填；填了以后，没做过覆盖的环境都继承它。
          </span>
        </label>

        <div v-else class="fld">
          <span class="fld-lb">全局值</span>
          <div class="readonly-box">在矩阵的「全局」列里修改，这里只改定义</div>
        </div>

        <div class="fld">
          <span class="fld-lb">
            必填
            <span class="fld-opt">为空时进工作台待办</span>
          </span>
          <div>
            <button
              type="button"
              class="req-pill"
              :class="{ on: varForm.isRequired }"
              @click="varForm.isRequired = !varForm.isRequired"
            >
              {{ varForm.isRequired ? '必填' : '选填' }}
            </button>
          </div>
        </div>

        <label class="fld">
          <span class="fld-lb">备注<span class="fld-opt">选填</span></span>
          <el-input v-model="varForm.remark" placeholder="如：数据库地址" />
        </label>
      </div>

      <template #footer>
        <div class="drawer-foot">
          <span class="foot-note">
            {{
              varForm.id
                ? '只改定义，不影响各环境已填的值'
                : '新增后可在矩阵里按环境单独覆盖'
            }}
          </span>
          <div class="foot-actions">
            <button class="btn" @click="varDrawer = false">取消</button>
            <button
              class="btn btn-primary"
              :disabled="saving || !!keyError"
              @click="saveVariable"
            >
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 粘贴导入 -->
    <el-drawer v-model="importDrawer" title="粘贴导入 dotenv" size="640px">
      <div class="imp-form">
        <label class="fld">
          <span class="fld-lb">
            dotenv 内容
            <span class="fld-opt">每行一个 KEY=值，# 开头为注释</span>
          </span>
          <el-input
            v-model="importText"
            type="textarea"
            :rows="10"
            class="mono-area"
            placeholder="DB_HOST=10.0.1.5&#10;DB_PORT=5432&#10;DB_PASSWORD=secret"
          />
        </label>

        <div class="fld">
          <span class="fld-lb">应用到</span>
          <div class="seg">
            <button
              type="button"
              :class="{ on: importApplyTo === 'GLOBAL' }"
              @click="importApplyTo = 'GLOBAL'"
            >
              全局值
            </button>
            <button
              type="button"
              :class="{ on: importApplyTo === 'ENV' }"
              @click="importApplyTo = 'ENV'"
            >
              指定环境
            </button>
          </div>
          <span class="fld-hint">
            {{
              importApplyTo === 'GLOBAL'
                ? '写入全局值，所有没做过覆盖的环境都会继承。'
                : '只写入所选环境的覆盖值，不动全局值。'
            }}
          </span>
        </div>

        <div v-if="importApplyTo === 'ENV'" class="fld">
          <span class="fld-lb">目标环境<span class="req">*</span></span>
          <el-select v-model="importEnvId" style="width: 100%" placeholder="选择环境">
            <el-option
              v-for="e in envs"
              :key="e.id"
              :label="`${e.name} · ${e.code}`"
              :value="e.id"
            />
          </el-select>
        </div>

        <div v-if="importText.trim()" class="imp-preview">
          <span class="pv-main">
            将导入 <b>{{ importPreview.valid }}</b> 个变量
          </span>
          <span v-if="importPreview.enc" class="pv-sub">
            · {{ importPreview.enc }} 个按加密存储
          </span>
          <span v-if="importPreview.bad" class="pv-warn">
            · {{ importPreview.bad }} 行格式不对，会跳过
          </span>
        </div>

        <div class="callout">
          <ProtoIcon name="alert" :size="13" class="c-ic" />
          <span>
            key 以 PASSWORD / SECRET / TOKEN / KEY 结尾时自动按加密存储；
            已存在的 key 只在目标作用域为空时补值，不会覆盖你已填的内容。
          </span>
        </div>
      </div>

      <template #footer>
        <div class="drawer-foot">
          <span class="foot-note">导入不会覆盖已填的值</span>
          <div class="foot-actions">
            <button class="btn" @click="importDrawer = false">取消</button>
            <button
              class="btn btn-primary"
              :disabled="saving || !importPreview.valid"
              @click="doImport"
            >
              {{ saving ? '导入中…' : '导入' }}
            </button>
          </div>
        </div>
      </template>
    </el-drawer>

    <!-- 模板 diff -->
    <el-drawer v-model="diffDrawer" title="模板同步" size="640px">
      <div v-if="!templateDiff" class="fc-empty">加载中…</div>
      <template v-else>
        <h4>新增（{{ templateDiff.added.length }}）</h4>
        <el-checkbox-group v-model="acceptAdded">
          <div v-for="v in templateDiff.added" :key="v.varKey" class="diff-row">
            <el-checkbox :value="v.varKey">
              <span class="mono">{{ v.varKey }}</span>
              <span class="muted"> — {{ v.remark || '无备注' }}</span>
            </el-checkbox>
          </div>
        </el-checkbox-group>
        <el-empty v-if="!templateDiff.added.length" description="无新增" :image-size="40" />

        <h4>定义变更（{{ templateDiff.changed.length }}）</h4>
        <el-checkbox-group v-model="acceptChanged">
          <div v-for="c in templateDiff.changed" :key="c.varKey" class="diff-row">
            <el-checkbox :value="c.varKey">
              <span class="mono">{{ c.varKey }}</span>
              <span class="muted">
                — 备注 {{ c.module.remark || '—' }} → {{ c.template.remark || '—' }}
                <template v-if="c.module.valueType !== c.template.valueType">
                  ；类型 {{ c.module.valueType }} → {{ c.template.valueType }}
                </template>
                <template v-if="c.module.isRequired !== c.template.isRequired">
                  ；必填 {{ c.module.isRequired ? '是' : '否' }} →
                  {{ c.template.isRequired ? '是' : '否' }}
                </template>
              </span>
            </el-checkbox>
          </div>
        </el-checkbox-group>
        <el-empty v-if="!templateDiff.changed.length" description="无定义变更" :image-size="40" />

        <h4>模板已删除（{{ templateDiff.removed.length }}）</h4>
        <el-checkbox-group v-model="removedKeys">
          <div v-for="v in templateDiff.removed" :key="v.varKey" class="diff-row">
            <el-checkbox :value="v.varKey">
              <span class="mono">{{ v.varKey }}</span>
            </el-checkbox>
          </div>
        </el-checkbox-group>
        <el-empty v-if="!templateDiff.removed.length" description="无删除项" :image-size="40" />
        <el-radio-group v-if="templateDiff.removed.length" v-model="removedAction">
          <el-radio value="DELETE">删除模块变量</el-radio>
          <el-radio value="KEEP">保留为模块自有变量</el-radio>
        </el-radio-group>

        <el-alert type="warning" :closable="false" show-icon style="margin-top: 16px">
          <template #title>同步永不覆盖已填的值</template>
          同步只更新变量定义（类型/备注/必填）与新增变量，不会修改任何已有的全局值或环境覆盖。
        </el-alert>
      </template>
      <template #footer>
        <el-button @click="diffDrawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSync">同步</el-button>
      </template>
    </el-drawer>

    <!-- 文件预览（只读）与基于版本另存 -->
    <FilePreviewDrawer
      v-model="previewOpen"
      :file="previewFile"
      :env-id="fileEnvId"
      :env-name="fileEnvName"
      :module-code="module?.code || ''"
      @saved="loadFiles"
    />

    <!-- 上传：先确认每个文件的备注 -->
    <el-dialog
      v-model="uploadDialog"
      :title="pendingFiles.length > 1 ? `上传 ${pendingFiles.length} 个文件` : '上传文件'"
      width="580px"
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
    >
      <div class="up-form">
        <div class="up-meta">
          目标环境 <b>{{ fileEnvName }}</b>
          <span v-if="uploading" class="up-progress">· 正在上传 {{ uploadDone }}/{{ pendingFiles.length }}</span>
        </div>

        <div class="up-list">
          <div
            v-for="p in pendingFiles"
            :key="p.uid"
            class="up-row"
            :class="{ failed: !!p.error }"
          >
            <div class="up-row-main">
              <div class="up-row-name mono" :title="p.file.name">{{ p.file.name }}</div>
              <div class="up-row-sub">
                <span>{{ formatSize(p.file.size) }}</span>
                <span v-if="p.error" class="up-err">{{ p.error }}</span>
                <span v-else class="up-ver">将生成 v{{ p.version }}</span>
              </div>
            </div>
            <el-input
              v-model="p.remark"
              size="small"
              class="up-remark"
              placeholder="备注（这次改了什么）"
              :disabled="uploading"
            />
            <button
              v-if="!uploading"
              class="up-del"
              title="从列表移除"
              @click="removePending(p.uid)"
            >
              移除
            </button>
          </div>
        </div>

        <div class="up-tip">
          共 {{ pendingFiles.length }} 个文件 · {{ formatSize(pendingTotalSize) }}
          · 同名文件逐个生成新版本，每个文件保留最近 3 个
        </div>
      </div>
      <template #footer>
        <button class="btn" :disabled="uploading" @click="closeUploadDialog">取消</button>
        <button
          class="btn btn-primary"
          :disabled="uploading || !pendingFiles.length"
          @click="confirmUpload"
        >
          {{ uploading ? `上传中 ${uploadDone}/${pendingFiles.length}` : `上传 ${pendingFiles.length} 个文件` }}
        </button>
      </template>
    </el-dialog>

    <ModuleFormDrawer
      v-model="editOpen"
      :kind="module?.isShared ? 'shared' : 'private'"
      :module="module"
      :environments="allEnvs"
      @saved="onModuleSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Aim,
  CopyDocument,
  Document,
  EditPen,
  Link,
  MoreFilled,
  Plus,
  RefreshRight,
  Upload
} from '@element-plus/icons-vue'
import CellEditor from '@/components/CellEditor.vue'
import MatrixToolbar from '@/components/MatrixToolbar.vue'
import InheritChain from '@/components/InheritChain.vue'
import ModuleFormDrawer from '@/components/ModuleFormDrawer.vue'
import FilePreviewDrawer from '@/components/FilePreviewDrawer.vue'
import ProtoIcon from '@/components/ProtoIcon.vue'
import { api, saveBlob } from '@/api/http'
import { useUserStore } from '@/stores/user'
import { envApi, type Env as EnvOption } from '@/api'
import { useDeepLink } from '@/composables/useDeepLink'
import type { Id } from '@/types/id'
import type { ChainData, Env, MatrixData, MatrixRow } from '@/types/module'

interface ModuleDetail {
  id: Id
  code: string
  name: string
  description?: string
  groupName?: string
  isShared: boolean
  applicationId?: number
  applicationCode?: string
  applicationName?: string
  updatedAt?: string
  templateId?: Id | null
  templateSyncedAt?: string | null
  environmentIds?: Id[]
}
interface FileItem {
  id: Id
  fileKey: string
  remark?: string
  currentVersion: number
}
/** 待上传队列项：一个文件一条，备注与版本各自独立 */
interface PendingUpload {
  uid: number
  file: File
  remark: string
  version: number
  error?: string
}
interface DiffChanged {
  varKey: string
  template: { valueType: string; remark?: string; isRequired: boolean }
  module: { valueType: string; remark?: string; isRequired: boolean }
}
interface TemplateDiff {
  added: Array<{ varKey: string; valueType: string; remark?: string }>
  changed: DiffChanged[]
  removed: Array<{ varKey: string }>
  hasUpdate: boolean
}

const route = useRoute()
const router = useRouter()
// 雪花 ID 超出 Number 安全范围，路由参数保持字符串原样透传
const moduleId = computed(() => String(route.params.id))

const module = ref<ModuleDetail | null>(null)
const editOpen = ref(false)
const matrix = ref<MatrixData | null>(null)
const templateDiff = ref<TemplateDiff | null>(null)
const loading = ref(true)
const saving = ref(false)

const rows = computed(() => matrix.value?.rows || [])
const envs = computed(() => matrix.value?.environments || [])
const allEnvs = ref<EnvOption[]>([])
const userStore = useUserStore()
const isAdmin = computed(() => userStore.isSystemAdmin())
const pathText = computed(() => {
  if (!module.value) return ''
  return module.value.applicationCode
    ? `${module.value.applicationCode} / ${module.value.code}`
    : module.value.code
})

const q = ref('')
const prefix = ref('')
const toolbarRef = ref<InstanceType<typeof MatrixToolbar> | null>(null)

const files = ref<FileItem[]>([])
const fileEnvId = ref<Id | null>(null)
const previewOpen = ref(false)
const previewFile = ref<{
  id: Id
  moduleId: Id
  fileKey: string
  currentVersion: number
} | null>(null)
const uploadDialog = ref(false)
const pendingFiles = ref<PendingUpload[]>([])
const uploading = ref(false)
const uploadDone = ref(0)
const fileInputRef = ref<HTMLInputElement | null>(null)
const dragActive = ref(false)
let dragDepth = 0
let pendingSeq = 0

const pendingTotalSize = computed(() =>
  pendingFiles.value.reduce((sum, p) => sum + p.file.size, 0)
)

const varDrawer = ref(false)
const importDrawer = ref(false)
const diffDrawer = ref(false)

const varForm = reactive<{
  id?: Id
  varKey: string
  valueType: 'PLAIN' | 'ENCRYPTED'
  remark: string
  isRequired: boolean
  globalValue: string
}>({ varKey: '', valueType: 'PLAIN', remark: '', isRequired: false, globalValue: '' })

const importText = ref('')

/** 仅用于导入前预览，最终解析以后端为准（规则见 DotenvCodec） */
const importPreview = computed(() => {
  let valid = 0
  let enc = 0
  let bad = 0
  for (const raw of importText.value.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq <= 0) {
      bad += 1
      continue
    }
    const key = line.slice(0, eq).trim()
    if (!key) {
      bad += 1
      continue
    }
    valid += 1
    const k = key.toUpperCase()
    if (
      k.endsWith('PASSWORD') ||
      k.endsWith('SECRET') ||
      k.endsWith('TOKEN') ||
      k.endsWith('KEY')
    ) {
      enc += 1
    }
  }
  return { valid, enc, bad }
})
const importApplyTo = ref<'GLOBAL' | 'ENV'>('GLOBAL')
const importEnvId = ref<number | null>(null)

const acceptAdded = ref<string[]>([])
const acceptChanged = ref<string[]>([])
const removedKeys = ref<string[]>([])
const removedAction = ref<'DELETE' | 'KEEP'>('KEEP')

// ---- 继承链 ----
const chainOpen = ref(false)
const chainAnchor = ref<HTMLElement | null>(null)
const chainData = ref<ChainData | null>(null)
const chainLoading = ref(false)
const chainRow = ref<MatrixRow | null>(null)
const chainEnv = ref<Env | null>(null)
type CellEditorInstance = { startEdit: () => void }
const cellRefs = new Map<string, CellEditorInstance>()

function setCellRef(rowId: Id, envId: Id, el: unknown) {
  const key = `${rowId}-${envId}`
  if (el && typeof (el as CellEditorInstance).startEdit === 'function') {
    cellRefs.set(key, el as CellEditorInstance)
  } else {
    cellRefs.delete(key)
  }
}

async function openChain(row: MatrixRow, env: Env, anchor: HTMLElement) {
  chainRow.value = row
  chainEnv.value = env
  chainAnchor.value = anchor
  chainData.value = null
  chainOpen.value = true
  chainLoading.value = true
  try {
    chainData.value = await api.get<ChainData>(
      `/api/console/modules/${moduleId.value}/variables/${row.id}/chain`,
      { environmentId: env.id }
    )
  } catch {
    chainData.value = null
  } finally {
    chainLoading.value = false
  }
}

function closeChain() {
  chainOpen.value = false
}

function onChainOverride() {
  const row = chainRow.value
  const env = chainEnv.value
  closeChain()
  if (!row || !env) return
  nextTick(() => cellRefs.get(`${row.id}-${env.id}`)?.startEdit())
}

// ---- 深链 ----
function clearFilters() {
  q.value = ''
  prefix.value = ''
}

function resolveDeepLink(varKey: string, envCode?: string) {
  const row =
    rows.value.find((r) => r.varKey.toLowerCase() === varKey.toLowerCase()) ||
    rows.value.find((r) => r.varKey.toLowerCase().includes(varKey.toLowerCase()))
  if (!row) return null
  let envId: string | number | null = null
  if (envCode) {
    if (envCode === 'global' || envCode === '0') {
      envId = 0
    } else {
      const env = envs.value.find(
        (e) => e.code === envCode || String(e.id) === String(envCode)
      )
      envId = env?.id ?? null
    }
  }
  return { rowId: row.id, envId }
}

const {
  info: deepLinkInfo,
  hitRowId,
  hitEnvId,
  apply: applyDeepLink,
  clear: clearDeepLinkState
} = useDeepLink(resolveDeepLink, clearFilters)

const deepEnvLabel = computed(() => {
  const code = deepLinkInfo.value?.envCode
  if (!code) return ''
  const env = envs.value.find((e) => e.code === code || String(e.id) === String(code))
  return env ? env.name : code
})

const appliedKey = ref('')

/** 最近点击的单元格：决定「复制此值链接」指向哪一格 */
const activeCell = ref<{ rowId: Id; envId: Id | null } | null>(null)

function markCell(row: MatrixRow, envId: Id | null) {
  activeCell.value = { rowId: row.id, envId }
}

function activeEnvIdOf(row: MatrixRow): Id | null {
  const a = activeCell.value
  return a && String(a.rowId) === String(row.id) ? a.envId : 0
}

function linkTargetLabel(row: MatrixRow) {
  const envId = activeEnvIdOf(row)
  if (envId === null || envId === 0) return '全局值'
  const env = envs.value.find((e) => String(e.id) === String(envId))
  return env ? env.name : '全局值'
}

function applyFromRoute(force = false) {
  const varKey = route.query.var
  if (!varKey) return
  const envCode = route.query.env
  const key = `${String(varKey)}|${envCode ? String(envCode) : ''}`
  if (!force && key === appliedKey.value) return
  appliedKey.value = key
  void applyDeepLink(String(varKey), envCode ? String(envCode) : null).then((ok) => {
    if (!ok) ElMessage.warning(`未找到变量 ${varKey}`)
  })
}

function clearDeepLink() {
  clearDeepLinkState()
  appliedKey.value = ''
  const query = { ...route.query }
  delete query.var
  delete query.env
  void router.replace({ query })
}

// ---- 筛选 ----
const filteredRows = computed(() => {
  const needle = q.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (prefix.value === '__empty') {
      if (!envCells(row).some((c) => !c || !c.found)) return false
    } else if (prefix.value === '__override') {
      if (!Object.values(row.cells).some((c) => c.override)) return false
    } else if (prefix.value && !row.varKey.startsWith(prefix.value)) {
      return false
    }
    if (!needle) return true
    if (row.varKey.toLowerCase().includes(needle)) return true
    if ((row.remark || '').toLowerCase().includes(needle)) return true
    // 只索引明文值：密文值搜不到（安全属性）
    return Object.values(row.cells).some(
      (c) => !c.masked && c.value && c.value.toLowerCase().includes(needle)
    )
  })
})

function envCells(row: MatrixRow) {
  return Object.entries(row.cells)
    .filter(([k]) => k !== '0')
    .map(([, c]) => c)
}

function missingCount(envId: Id) {
  return rows.value.filter((r) => {
    if (!r.isRequired) return false
    const c = r.cells[String(envId)]
    return !c || !c.found
  }).length
}

// ---- 拖拽排序（只用于排序，结果=位置且可撤销） ----
const dragRowId = ref<Id | null>(null)
const overRowId = ref<Id | null>(null)

function onDragStart(e: DragEvent, row: MatrixRow) {
  dragRowId.value = row.id
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(row.id))
  }
}

function onDragOver(row: MatrixRow) {
  if (dragRowId.value && dragRowId.value !== row.id) overRowId.value = row.id
}

function onDragEnd() {
  dragRowId.value = null
  overRowId.value = null
}

async function onDrop(target: MatrixRow) {
  const id = dragRowId.value
  onDragEnd()
  if (!id || id === target.id || !matrix.value) return
  const list = [...matrix.value.rows]
  const from = list.findIndex((r) => r.id === id)
  const to = list.findIndex((r) => r.id === target.id)
  if (from < 0 || to < 0) return
  const [moved] = list.splice(from, 1)
  list.splice(to, 0, moved)
  matrix.value.rows = list
  try {
    await api.put(`/api/console/modules/${moduleId.value}/variables/sort`, {
      ids: list.map((r) => r.id)
    })
    ElMessage.success('顺序已更新')
  } catch {
    await loadMatrix()
  }
}

// ---- 数据加载 ----
function fmt(v?: string) {
  if (!v) return '—'
  return new Date(v).toLocaleString('zh-CN', { hour12: false })
}

function formatSize(n?: number) {
  if (!n) return '—'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

async function loadModule() {
  try {
    module.value = await api.get<ModuleDetail>(
      `/api/console/admin/modules/${moduleId.value}`
    )
  } catch {
    // 非管理员看不到 admin 详情接口；矩阵仍可加载
  }
}

async function onModuleCommand(cmd: string) {
  if (cmd !== 'delete') return
  const name = module.value?.code || ''
  try {
    await ElMessageBox.confirm(
      `删除模块「${name}」？模块的变量和文件会一并删除。` +
        (module.value?.isShared ? '共享模块若仍有订阅方，需要先取消订阅。' : ''),
      '删除模块',
      { type: 'warning', confirmButtonText: '删除' }
    )
    await api.del(`/api/console/admin/modules/${moduleId.value}`)
    ElMessage.success('已删除')
    router.push('/modules')
  } catch {
    // 取消或后端拒绝
  }
}

async function onModuleSaved() {
  await Promise.all([loadModule(), loadMatrix()])
  if (fileEnvId.value && !envs.value.some((e) => e.id === fileEnvId.value)) {
    fileEnvId.value = envs.value[0]?.id ?? null
  }
  await loadFiles()
}

async function loadMatrix() {
  loading.value = true
  try {
    matrix.value = await api.get<MatrixData>(
      `/api/console/modules/${moduleId.value}/variables`
    )
    if (!fileEnvId.value && envs.value.length) {
      fileEnvId.value = envs.value[0].id
    }
  } finally {
    loading.value = false
  }
}

async function loadTemplateDiff() {
  // 未关联模板的模块不请求 diff：接口会返回 400 并弹全局错误提示
  if (!module.value?.templateId) {
    templateDiff.value = null
    return
  }
  try {
    templateDiff.value = await api.get<TemplateDiff>(
      `/api/console/admin/modules/${moduleId.value}/template-diff`
    )
  } catch {
    templateDiff.value = null
  }
}

async function loadFiles() {
  if (!fileEnvId.value) return
  files.value = await api.get<FileItem[]>(
    `/api/console/modules/${moduleId.value}/files`,
    { environmentId: fileEnvId.value }
  )
}

// ---- 变量 ----
function openAddVariable() {
  varForm.id = undefined
  varForm.varKey = ''
  varForm.valueType = 'PLAIN'
  varForm.remark = ''
  varForm.isRequired = false
  varForm.globalValue = ''
  varDrawer.value = true
}

function editVariable(row: MatrixRow) {
  varForm.id = row.id
  varForm.varKey = row.varKey
  varForm.valueType = row.valueType
  varForm.remark = row.remark || ''
  varForm.isRequired = row.isRequired
  varForm.globalValue = ''
  varDrawer.value = true
}

const keyError = computed(() => {
  const k = varForm.varKey.trim()
  if (!k) return ''
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) {
    return '只能包含字母、数字、下划线，且不能以数字开头'
  }
  const dup = rows.value.some(
    (r) => r.id !== varForm.id && r.varKey.toUpperCase() === k.toUpperCase()
  )
  return dup ? '已存在同名变量' : ''
})

async function saveVariable() {
  varForm.varKey = varForm.varKey.trim()
  if (!varForm.varKey) {
    ElMessage.warning('Key 必填')
    return
  }
  if (keyError.value) {
    ElMessage.warning(keyError.value)
    return
  }
  saving.value = true
  try {
    if (varForm.id) {
      await api.put(
        `/api/console/modules/${moduleId.value}/variables/${varForm.id}`,
        {
          valueType: varForm.valueType,
          remark: varForm.remark,
          isRequired: varForm.isRequired
        }
      )
    } else {
      await api.post(`/api/console/modules/${moduleId.value}/variables`, {
        varKey: varForm.varKey,
        valueType: varForm.valueType,
        remark: varForm.remark,
        isRequired: varForm.isRequired,
        globalValue: varForm.globalValue || null
      })
    }
    ElMessage.success('已保存')
    varDrawer.value = false
    await loadMatrix()
  } finally {
    saving.value = false
  }
}

async function onRowCommand(cmd: string, row: MatrixRow) {
  if (cmd === 'edit') {
    editVariable(row)
  } else if (cmd === 'copylink') {
    void copyLink(row)
  } else if (cmd === 'delete') {
    await ElMessageBox.confirm(
      `确认删除变量 ${row.varKey}？其覆盖值也会被删除。`,
      '删除变量',
      { type: 'warning' }
    )
    await api.del(`/api/console/modules/${moduleId.value}/variables/${row.id}`)
    ElMessage.success('已删除')
    await loadMatrix()
  } else if (cmd === 'up' || cmd === 'down') {
    const list = [...rows.value]
    const idx = list.findIndex((r) => r.id === row.id)
    const target = cmd === 'up' ? idx - 1 : idx + 1
    if (idx < 0 || target < 0 || target >= list.length) return
    ;[list[idx], list[target]] = [list[target], list[idx]]
    await api.put(`/api/console/modules/${moduleId.value}/variables/sort`, {
      ids: list.map((r) => r.id)
    })
    await loadMatrix()
  }
}

// ---- 导入 / 导出 ----
async function doImport() {
  if (!importText.value.trim()) {
    ElMessage.warning('请粘贴 dotenv 内容')
    return
  }
  if (importApplyTo.value === 'ENV' && !importEnvId.value) {
    ElMessage.warning('请选择环境')
    return
  }
  saving.value = true
  try {
    await api.post(`/api/console/modules/${moduleId.value}/variables/import`, {
      dotenvText: importText.value,
      applyTo: importApplyTo.value,
      environmentId: importApplyTo.value === 'ENV' ? importEnvId.value : null
    })
    ElMessage.success('已导入')
    importDrawer.value = false
    importText.value = ''
    await loadMatrix()
  } finally {
    saving.value = false
  }
}

async function copyDotenv() {
  const envId = fileEnvId.value || envs.value[0]?.id
  if (!envId) {
    ElMessage.warning('请先给模块绑定环境')
    return
  }
  const res = await api.get<{ dotenv: string }>(
    `/api/console/modules/${moduleId.value}/variables/export`,
    { environmentId: envId }
  )
  try {
    await navigator.clipboard.writeText(res.dotenv)
    ElMessage.success('dotenv 已复制到剪贴板')
  } catch {
    await ElMessageBox.alert(res.dotenv, 'dotenv', { customClass: 'fc-mono' })
  }
}

function buildLink(row?: MatrixRow) {
  const params = new URLSearchParams()
  if (row) {
    params.set('var', row.varKey)
    const envId = activeEnvIdOf(row)
    if (envId === 0) {
      params.set('env', 'global')
    } else if (envId) {
      const env = envs.value.find((e) => String(e.id) === String(envId))
      if (env) params.set('env', env.code)
    }
  } else {
    // 页头按钮：当前「文件」面板选中的环境
    const env = envs.value.find((e) => e.id === fileEnvId.value) || envs.value[0]
    if (env) params.set('env', env.code)
  }
  const qs = params.toString()
  return `${location.origin}/modules/${moduleId.value}${qs ? '?' + qs : ''}`
}

async function copyLink(row?: MatrixRow) {
  const link = buildLink(row)
  const suffix = row ? ` · ${linkTargetLabel(row)}` : ''
  try {
    await navigator.clipboard.writeText(link)
    ElMessage.success(`已复制深链${suffix}`)
  } catch {
    await ElMessageBox.alert(link, '深链')
  }
}

// ---- 模板同步 ----
async function openTemplateDiff() {
  await loadTemplateDiff()
  acceptAdded.value = []
  acceptChanged.value = []
  removedKeys.value = []
  removedAction.value = 'KEEP'
  diffDrawer.value = true
}

async function doSync() {
  saving.value = true
  try {
    await api.post(`/api/console/admin/modules/${moduleId.value}/template-sync`, {
      acceptAdded: acceptAdded.value,
      acceptChanged: acceptChanged.value,
      removedKeys: removedKeys.value,
      removedAction: removedAction.value
    })
    ElMessage.success('同步完成')
    diffDrawer.value = false
    await Promise.all([loadMatrix(), loadTemplateDiff()])
  } finally {
    saving.value = false
  }
}

// ---- 文件 ----
const MAX_FILE_MB = 50
/** 单模块文件总量上限，与后端 FileService 保持一致 */
const MAX_MODULE_TOTAL_MB = 500

const fileEnvName = computed(
  () => envs.value.find((e) => String(e.id) === String(fileEnvId.value))?.name || ''
)

function defaultUploadRemark() {
  return `手工上传 · ${new Date().toLocaleString('zh-CN', { hour12: false })}`
}

/** 同一批里重复拖入同名文件要顺延版本号，否则两个都算成 v4 */
function nextVersionFor(name: string) {
  const existing = files.value.find((x) => x.fileKey === name)?.currentVersion || 0
  const queued = pendingFiles.value.filter((p) => p.file.name === name).length
  return existing + queued + 1
}

function openFilePicker() {
  if (!fileEnvId.value) {
    ElMessage.warning('先选择环境再上传')
    return
  }
  fileInputRef.value?.click()
}

function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.length) addFiles(input.files)
  // 清空，保证同一个文件再次选择仍触发 change
  input.value = ''
}

/** 入队前统一校验，校验通过再弹确认框 */
function addFiles(list: FileList | File[]) {
  if (!fileEnvId.value) {
    ElMessage.warning('先选择环境再上传')
    return
  }
  const incoming = Array.from(list)
  if (!incoming.length) return

  const tooBig: string[] = []
  const duplicated: string[] = []
  let added = 0

  for (const file of incoming) {
    if (file.size > MAX_FILE_MB * 1024 * 1024) {
      tooBig.push(`${file.name}（${formatSize(file.size)}）`)
      continue
    }
    const exists = pendingFiles.value.some(
      (p) =>
        p.file.name === file.name &&
        p.file.size === file.size &&
        p.file.lastModified === file.lastModified
    )
    if (exists) {
      duplicated.push(file.name)
      continue
    }
    if (pendingTotalSize.value + file.size > MAX_MODULE_TOTAL_MB * 1024 * 1024) {
      ElMessage.error(`这一批已超过单模块 ${MAX_MODULE_TOTAL_MB}MB 上限，后面的文件没有加入`)
      break
    }
    pendingFiles.value.push({
      uid: ++pendingSeq,
      file,
      remark: defaultUploadRemark(),
      version: nextVersionFor(file.name)
    })
    added++
  }

  if (tooBig.length) {
    ElMessage.error(`单文件不能超过 ${MAX_FILE_MB}MB：${tooBig.join('、')}`)
  }
  if (duplicated.length) {
    ElMessage.warning(`已在待上传列表里，已忽略：${duplicated.join('、')}`)
  }
  if (added) uploadDialog.value = true
}

function removePending(uid: number) {
  pendingFiles.value = pendingFiles.value.filter((p) => p.uid !== uid)
  if (!pendingFiles.value.length) uploadDialog.value = false
}

function closeUploadDialog() {
  if (uploading.value) return
  uploadDialog.value = false
  pendingFiles.value = []
}

/** 逐个串行上传：失败不打断队列，避免同一 fileKey 的版本号撞车 */
async function confirmUpload() {
  if (uploading.value || !pendingFiles.value.length) return
  const envId = String(fileEnvId.value)
  uploading.value = true
  uploadDone.value = 0
  let ok = 0
  for (const item of [...pendingFiles.value]) {
    item.error = ''
    try {
      const form = new FormData()
      form.append('file', item.file)
      form.append('fileKey', item.file.name)
      form.append('environmentId', envId)
      form.append('remark', (item.remark || '').trim())
      await api.upload(`/api/console/modules/${moduleId.value}/files`, form)
      pendingFiles.value = pendingFiles.value.filter((p) => p.uid !== item.uid)
      ok++
    } catch (err) {
      item.error = err instanceof Error && err.message ? err.message : '上传失败'
      // 失败项版本号可能因前面的成功项变化，重算一次
      item.version = nextVersionFor(item.file.name)
    } finally {
      uploadDone.value++
    }
  }
  uploading.value = false
  await loadFiles()
  if (!pendingFiles.value.length) {
    uploadDialog.value = false
    ElMessage.success(`已上传 ${ok} 个文件`)
  } else {
    ElMessage.error(`${ok} 个成功，${pendingFiles.value.length} 个失败，可修正后重试`)
  }
}

// ---- 拖拽上传：整块文件列表就是投放区 ----
function isFileDrag(e: DragEvent) {
  return Array.from(e.dataTransfer?.types || []).includes('Files')
}

function onDragEnter(e: DragEvent) {
  // 表格行排序也是 drag，只认带 Files 的拖拽
  if (!isFileDrag(e) || !fileEnvId.value) return
  dragDepth++
  dragActive.value = true
}

function onDragLeave(e: DragEvent) {
  // 拖出窗口时不一定会成对触发 dragleave，relateTarget 为空直接收摊
  if (!e.relatedTarget) {
    resetDrag()
    return
  }
  if (!isFileDrag(e)) return
  dragDepth = Math.max(0, dragDepth - 1)
  if (!dragDepth) dragActive.value = false
}

function resetDrag() {
  dragDepth = 0
  dragActive.value = false
}

function onDropFiles(e: DragEvent) {
  resetDrag()
  if (!fileEnvId.value) {
    ElMessage.warning('先选择环境再上传')
    return
  }
  if (e.dataTransfer?.files?.length) addFiles(e.dataTransfer.files)
}

function openPreview(row: FileItem) {
  previewFile.value = {
    id: row.id,
    moduleId: moduleId.value,
    fileKey: row.fileKey,
    currentVersion: row.currentVersion
  }
  previewOpen.value = true
}

async function downloadFile(row: FileItem) {
  const blob = await api.download(`/api/console/files/${row.id}/download`)
  saveBlob(blob, String(row.fileKey || 'file'))
}

async function deleteFile(row: FileItem) {
  await ElMessageBox.confirm(
    `确认删除文件 ${row.fileKey}？其全部版本也会被删除。`,
    '删除文件',
    { type: 'warning' }
  )
  await api.del(`/api/console/files/${row.id}`)
  ElMessage.success('已删除')
  await loadFiles()
}


// ---- 快捷键 ----
function onGlobalKey(e: KeyboardEvent) {
  if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey) return
  const el = document.activeElement as HTMLElement | null
  if (el && (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable)) return
  e.preventDefault()
  toolbarRef.value?.focus()
}

watch(
  () => [route.query.var, route.query.env],
  () => applyFromRoute()
)

watch(moduleId, async (id, old) => {
  if (!id || id === old) return
  clearDeepLinkState()
  appliedKey.value = ''
  fileEnvId.value = null
  await Promise.all([loadModule(), loadMatrix()])
  await loadTemplateDiff()
  await loadFiles()
  applyFromRoute(true)
})

onMounted(async () => {
  document.addEventListener('keydown', onGlobalKey)
  // 兜底：拖到窗口外松手，容器收不到 drop，靠全局事件清掉遮罩
  document.addEventListener('dragend', resetDrag)
  document.addEventListener('drop', resetDrag)
  try {
    allEnvs.value = await envApi.list()
  } catch {
    allEnvs.value = []
  }
  await Promise.all([loadModule(), loadMatrix()])
  await loadTemplateDiff()
  await loadFiles()
  applyFromRoute(true)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onGlobalKey)
  document.removeEventListener('dragend', resetDrag)
  document.removeEventListener('drop', resetDrag)
})
</script>

<style scoped>
.head-main {
  min-width: 0;
}

.path-inline {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-3);
}

.ph-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  padding-top: 2px;
}

.page-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 5px;
  font-size: 12px;
  color: var(--text-3);
}

.page-meta .mono {
  font-size: 12px;
  color: var(--text-2);
}

.page-meta .sep {
  color: #c9cdd6;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.deep-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border: 1px solid var(--primary-100);
  border-radius: var(--r-sm);
  background: var(--primary-50);
  font-size: 12.5px;
  color: var(--text-2);
}

.deep-bar b {
  color: var(--primary-700);
  font-weight: 600;
}

.deep-bar button {
  margin-left: auto;
  border: 0;
  background: none;
  padding: 0;
  color: var(--primary);
  font-size: 12px;
  cursor: pointer;
}

.tpl-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 9px 12px;
  border: 1px solid #fce6b5;
  border-radius: var(--r-sm);
  background: var(--warn-50);
}

.tpl-ic {
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  border-radius: 6px;
  background: #fff;
  color: var(--warn);
  display: grid;
  place-items: center;
}

.tpl-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12.5px;
  color: var(--warn);
}

.tpl-main b {
  font-weight: 600;
}

.tpl-detail {
  color: #8a5a10;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tpl-btn {
  margin-left: auto;
  flex: 0 0 auto;
  border: 0;
  background: none;
  color: var(--warn);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}

.matrix-wrap {
  max-height: calc(100vh - 326px);
  overflow: auto;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r);
  box-shadow: var(--sh-1);
}

.matrix {
  width: 100%;
  min-width: 1072px;
  table-layout: fixed;
  border-collapse: separate;
  border-spacing: 0;
}

.matrix th {
  position: sticky;
  top: 0;
  z-index: 3;
  height: 38px;
  padding: 0 6px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
  text-align: left;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-2);
  white-space: nowrap;
}

.matrix td {
  height: 46px;
  padding: 0 6px;
  border-bottom: 1px solid #f0f1f5;
  background: var(--surface);
  vertical-align: middle;
}

.matrix tbody tr:last-child td {
  border-bottom: 0;
}

.matrix tbody tr:hover td {
  background: #fbfcfe;
}

.c-drag {
  width: 28px;
  padding: 0;
  text-align: center;
  position: sticky;
  left: 0;
  z-index: 2;
  background: var(--surface);
}

.c-key {
  width: 198px;
  position: sticky;
  left: 28px;
  z-index: 2;
  background: var(--surface);
}

.matrix thead .c-drag,
.matrix thead .c-key {
  z-index: 4;
  background: var(--surface-2);
}

.matrix tbody tr:hover .c-drag,
.matrix tbody tr:hover .c-key {
  background: #fbfcfe;
}

.c-type {
  width: 62px;
}

.c-remark {
  width: 128px;
}

.c-global {
  width: 154px;
}

.c-env {
  width: 118px;
}

.c-ops {
  width: 30px;
  padding: 0;
}

.grip {
  display: inline-block;
  color: #c9cdd6;
  font-size: 15px;
  line-height: 1;
  cursor: grab;
  opacity: 0;
  transition: opacity 0.14s;
  user-select: none;
}

.matrix tbody tr:hover .grip {
  opacity: 1;
}

.matrix tr.dragging td {
  opacity: 0.45;
}

.matrix tr.drag-over td {
  box-shadow: inset 0 2px 0 var(--primary);
}

.key-wrap {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}

.key {
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: -0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.req {
  margin-left: 2px;
  color: var(--danger);
  font-size: 13px;
  font-weight: 700;
}

.remark {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-3);
}

.env-head {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font: 600 11.5px var(--mono);
  color: var(--text-2);
}

.env-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 99px;
}

.env-name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.env-default {
  font: 500 9.5px var(--sans);
  color: var(--text-3);
  background: var(--surface-3);
  border-radius: 4px;
  padding: 0 3px;
  line-height: 13px;
}

.env-count {
  margin-left: 1px;
  padding: 0 4px;
  border-radius: 99px;
  background: var(--danger);
  color: #fff;
  font: 700 10px var(--sans);
  line-height: 14px;
}

.cell-hit {
  background: #f3f7ff !important;
}

.cell-hit :deep(.cell) {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.35);
}

.cell-hit :deep(.cell.is-val) {
  background: #fff;
}

.row-hit td {
  background: #f7faff !important;
}

.row-hit .c-drag,
.row-hit .c-key {
  background: #f7faff !important;
}

.row-more {
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: none;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}

.row-more:hover {
  background: var(--surface-3);
  color: var(--text);
}

.mx-empty td {
  height: 112px;
  text-align: center;
  background: var(--surface);
}

.mx-empty .e-t {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
}

.mx-empty .e-s {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.mx-empty .e-btn {
  margin-top: 8px;
  border: 0;
  background: none;
  padding: 0;
  color: var(--primary);
  font-size: 12.5px;
  cursor: pointer;
}

/* 文件 / 成员双栏 */
.panels {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  margin-top: 16px;
}

.panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--border);
}

.panel-t {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  font-weight: 600;
}

.panel-t .n {
  padding: 0 6px;
  border-radius: 5px;
  background: var(--surface-3);
  color: var(--text-3);
  font-size: 11px;
  font-weight: 600;
  line-height: 17px;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.file-list,
.member-list {
  padding: 6px;
}

.file-list {
  position: relative;
  min-height: 88px;
  transition: background 0.12s;
}

.file-list.drop-on {
  background: var(--primary-50);
}

.drop-zone {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  padding: 9px 10px;
  border: 1.5px dashed var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--text-3);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color 0.12s,
    background 0.12s,
    color 0.12s;
}

.drop-zone:hover:not(:disabled) {
  border-color: var(--primary);
  background: var(--primary-50);
  color: var(--primary);
}

.drop-zone:focus-visible {
  outline: 2px solid var(--primary-100);
  outline-offset: 1px;
}

.drop-zone:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.drop-zone.is-empty {
  flex-direction: column;
  gap: 3px;
  padding: 20px 12px;
}

.drop-zone:hover:not(:disabled) .dz-t {
  color: var(--primary);
}

.drop-zone + .file-row {
  margin-top: 2px;
}

.dz-t {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-2);
}

.dz-s {
  font-size: 11px;
  color: var(--text-3);
}

.drop-layer {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  border: 1.5px dashed var(--primary);
  border-radius: var(--r-sm);
  background: rgba(239, 244, 255, 0.86);
  pointer-events: none;
  z-index: 2;
}

.drop-box {
  text-align: center;
  color: var(--primary-700);
}

.drop-t {
  margin-top: 4px;
  font-size: 13px;
  font-weight: 600;
}

.drop-t b {
  color: var(--primary-700);
}

.drop-s {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
}

.file-row,
.member-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px;
  border-radius: var(--r-sm);
}

.file-row:hover,
.member-row:hover {
  background: var(--surface-2);
}

.file-ic {
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  border-radius: 6px;
  background: var(--surface-3);
  color: var(--text-2);
  display: grid;
  place-items: center;
}

.file-main,
.member-main {
  min-width: 0;
  flex: 1;
}

.file-main {
  cursor: pointer;
}

.file-key,
.member-nm {
  font-size: 12.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-sub,
.member-em {
  margin-top: 1px;
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-ops {
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.14s;
}

.file-row:hover .file-ops {
  opacity: 1;
}

.file-ops button,
.m-remove {
  border: 0;
  background: none;
  padding: 2px 4px;
  border-radius: 4px;
  color: var(--text-2);
  font-size: 11.5px;
  cursor: pointer;
}

.file-ops button:hover,
.m-remove:hover {
  background: var(--surface-3);
  color: var(--text);
}

.file-ops .danger,
.m-remove {
  color: var(--danger);
}

.file-ops .danger:hover,
.m-remove:hover {
  background: var(--danger-50);
  color: var(--danger);
}

.panel-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3);
}

.up-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.up-meta {
  font-size: 12px;
  color: var(--text-3);
}

.up-meta b {
  color: var(--text);
  font-weight: 600;
}

.up-progress {
  color: var(--primary);
}

.up-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 46vh;
  overflow-y: auto;
  padding-right: 2px;
}

.up-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 210px auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
}

.up-row.failed {
  border-color: #fecaca;
  background: var(--danger-50);
}

.up-row-main {
  min-width: 0;
}

.up-row-name {
  font-size: 12.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.up-row-sub {
  display: flex;
  gap: 8px;
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-3);
  min-width: 0;
}

.up-ver {
  color: var(--primary);
}

.up-err {
  color: var(--danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.up-del {
  border: 0;
  background: none;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11.5px;
  color: var(--text-3);
  cursor: pointer;
}

.up-del:hover {
  background: var(--surface-3);
  color: var(--danger);
}

.up-tip {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-3);
}

.up-tip b {
  color: var(--text-2);
}

.panel-foot {
  margin-top: auto;
  padding: 9px 14px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--text-3);
}

.member-row .el-avatar {
  flex: 0 0 26px;
  background: #e8edf7;
  color: #3b5b9a;
  font-size: 11px;
  font-weight: 600;
}

.diff-row {
  padding: 4px 0;
}

h4 {
  margin: 16px 0 8px;
  font-size: 13.5px;
  font-weight: 600;
}

.muted {
  color: var(--text-3);
}

@media (max-width: 1180px) {
  .panels {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 变量表单（与模板编辑抽屉同一套语言） */
.var-form {
  display: flex;
  flex-direction: column;
}

.fld {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 16px;
}

.fld:last-child {
  margin-bottom: 0;
}

.fld-lb {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-2);
}

.fld-opt {
  font-size: 11px;
  font-weight: 400;
  color: var(--text-3);
}

.fld-hint {
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--text-3);
}

.req {
  color: var(--danger);
  font-style: normal;
}

.err-text {
  font-size: 11.5px;
  color: var(--danger);
}

.k-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-size: 12.5px;
}

.k-input.bad :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--danger) inset !important;
}

.seg {
  display: flex;
  gap: 2px;
  height: 32px;
  max-width: 280px;
  padding: 2px;
  border: 1px solid var(--border-2);
  border-radius: var(--r-sm);
  background: var(--surface-3);
}

.seg button {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-family: inherit;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
  transition: 0.12s;
}

.seg button:hover {
  color: var(--text);
}

.seg button.on {
  background: var(--surface);
  color: var(--primary);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.1);
}

.req-pill {
  height: 28px;
  padding: 0 10px;
  border: 1px dashed var(--border-2);
  border-radius: 5px;
  background: transparent;
  color: var(--text-3);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: 0.12s;
}

.req-pill:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.req-pill.on {
  border: 1px solid var(--primary-100);
  background: var(--primary-50);
  color: var(--primary-700);
  font-weight: 600;
}

.readonly-box {
  display: flex;
  align-items: center;
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--text-3);
  font-size: 12.5px;
}

.drawer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.foot-note {
  font-size: 11.5px;
  color: var(--text-3);
}

.foot-actions {
  display: flex;
  gap: 8px;
}

/* 粘贴导入 */
.imp-form {
  display: flex;
  flex-direction: column;
}

.mono-area :deep(.el-textarea__inner) {
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.7;
}

.imp-preview {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px;
  margin: -4px 0 10px;
  font-size: 11.5px;
  color: var(--text-2);
}

.pv-main b {
  font-size: 13px;
  color: var(--primary-700);
  font-variant-numeric: tabular-nums;
}

.pv-sub {
  color: var(--text-3);
}

.pv-warn {
  color: var(--warn);
}

.callout {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 9px 11px;
  border-radius: var(--r-sm);
  background: var(--primary-50);
  border: 1px solid var(--primary-100);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.55;
}

.callout .c-ic {
  margin-top: 1px;
  color: var(--primary);
}
</style>
