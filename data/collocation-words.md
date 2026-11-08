# 搭配引入词清单（GPT reply-12 要求）

字段：word / lemma / 是否屈折 / 在1208词库 / 在400拼写白名单 / 出现次数 / 场景 / 识别优先级 / 拼写建议 / 首次出现

| word | lemma | 变体 | 1208 | 400 | 次数 | 场景 | 优先级 | 拼写 | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| meeting | meeting |  | N | N | 6 | work | P3-词库外,先做场景词不建卡 | N | c-am-02 |
| room | room |  | Y | Y | 5 | travel,work | P1-已在拼写白名单 | Y | c-like-03 |
| here | here |  | N | N | 4 | daily,request | P3-词库外,先做场景词不建卡 | N | c-am-01 |
| booking | book | 屈折/变体→原形 | Y | Y | 4 | travel | P1-已在拼写白名单 | Y | c-want-02 |
| receipt | receipt |  | N | N | 4 | travel | P3-词库外,先做场景词不建卡 | N | c-cani-02 |
| change | change |  | Y | Y | 3 | travel | P1-已在拼写白名单 | Y | c-want-02 |
| question | question |  | Y | Y | 2 | daily,work | P1-已在拼写白名单 | Y | c-have-03 |
| form | form |  | Y | Y | 2 | work | P1-已在拼写白名单 | Y | c-have-03 |
| working | work | 屈折/变体→原形 | Y | Y | 2 | work | P1-已在拼写白名单 | Y | c-like-01 |
| coffee | coffee |  | Y | N | 2 | daily | P2-词库内,按需引入 | N | c-like-02 |
| book | book |  | Y | Y | 2 | travel | P1-已在拼写白名单 | Y | c-want-01 |
| have | have |  | N | N | 2 | travel | P0-功能词(专项教学,不进FSRS) | N | c-cani-02 |
| luggage | luggage |  | Y | N | 2 | travel | P2-词库内,按需引入 | N | c-where-03 |
| some | some |  | N | N | 2 | daily | P3-词库外,先做场景词不建卡 | N | c-ineed-03 |
| help | help |  | Y | Y | 2 | daily,work | P1-已在拼写白名单 | Y | c-ineed-03 |
| work | work |  | Y | Y | 2 | work | P1-已在拼写白名单 | Y | c-workat-01 |
| assistant | assistant |  | N | N | 2 | work | P3-词库外,先做场景词不建卡 | N | c-workat-02 |
| office | office |  | Y | Y | 2 | work | P1-已在拼写白名单 | Y | c-workat-03 |
| account | account |  | N | N | 2 | work | P3-词库外,先做场景词不建卡 | N | c-helpwith-02 |
| new | new |  | Y | Y | 1 | daily | P1-已在拼写白名单 | Y | c-am-01 |
| ready | ready |  | Y | N | 1 | work | P2-词库内,按需引入 | N | c-am-02 |
| free | free |  | Y | Y | 1 | work | P1-已在拼写白名单 | Y | c-am-03 |
| afternoon | afternoon |  | Y | N | 1 | work | P2-词库内,按需引入 | N | c-am-03 |
| ten | ten |  | Y | N | 1 | work | P2-词库内,按需引入 | N | c-have-01 |
| time | time |  | Y | Y | 1 | daily | P1-已在拼写白名单 | Y | c-have-02 |
| today | today |  | Y | Y | 1 | daily | P1-已在拼写白名单 | Y | c-have-02 |
| team | team |  | Y | Y | 1 | work | P1-已在拼写白名单 | Y | c-like-01 |
| morning | morning |  | Y | Y | 1 | daily | P1-已在拼写白名单 | Y | c-like-02 |
| ask | ask |  | Y | Y | 1 | daily | P1-已在拼写白名单 | Y | c-want-03 |
| look | look |  | Y | Y | 1 | request | P1-已在拼写白名单 | Y | c-cani-01 |
| menu | menu |  | Y | N | 1 | request | P2-词库内,按需引入 | N | c-cani-01 |
| sit | sit |  | Y | Y | 1 | request | P1-已在拼写白名单 | Y | c-cani-03 |
| toilet | toilet |  | Y | N | 1 | request | P2-词库内,按需引入 | N | c-where-01 |
| train | train |  | Y | N | 1 | travel | P2-词库内,按需引入 | N | c-where-02 |
| station | station |  | Y | N | 1 | travel | P2-词库内,按需引入 | N | c-where-02 |
| night | night |  | Y | Y | 1 | travel | P1-已在拼写白名单 | Y | c-howmuch-01 |
| bag | bag |  | Y | N | 1 | travel | P2-词库内,按需引入 | N | c-howmuch-02 |
| ticket | ticket |  | Y | N | 1 | travel | P2-词库内,按需引入 | N | c-howmuch-03 |
| table | table |  | Y | Y | 1 | travel | P1-已在拼写白名单 | Y | c-idlike-03 |
| cheap | cheap |  | Y | N | 1 | travel | P2-词库内,按需引入 | N | c-looking-02 |
| hotel | hotel |  | Y | N | 1 | travel | P2-词库内,按需引入 | N | c-looking-02 |
| company | company |  | Y | Y | 1 | work | P1-已在拼写白名单 | Y | c-workat-01 |
| tool | tool |  | Y | N | 1 | work | P2-词库内,按需引入 | N | c-helpwith-03 |

## 结论
- 功能词（P0）：have —— 走句型线专项教学，不进词汇 FSRS、不占 50 动作额度。
- 词库外（P3）：meeting、here、receipt、some、assistant、account —— 先做场景表达里可看可用的词，不建独立卡、不强制背。
- 屈折形式已归并到原形，不新增词条（working→work、booking→book 等）。
