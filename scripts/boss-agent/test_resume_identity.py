import unittest
from resume_storage import person_name, filename_name, identity, original_filename, ocr_identity


class IdentityTest(unittest.TestCase):
    def test_labels_spacing_and_column_order(self):
        for text, name in [
            ('个人简历\n姓名：张晓林\n联系电话：保密', '张晓林'),
            ('姓 名\n陈 佳 宁\n工作经历', '陈佳宁'),
            ('联系方式\n求职意向：销售代表\nHELLO\n张晓林 男 | 37岁\n个人优势', '张晓林'),
            ('某学院 本科 数字媒体技术 2021-2025\n某公司 行政前台\n陈佳宁\n女 | 年龄：24岁 |\n教育经历', '陈佳宁'),
            ('联系方式\n求职信息\n资格证书\nR E S U M E\n苏琪\n电话：保密\n工作经历', '苏琪'),
            ('abc123abc123abc123abc123abc123~~\n'*80+'张晓林\n男 | 37岁\n工作经历', '张晓林'),
            ('李云跃\nPERSONAL RESUME\n性别：男\n教育经历', '李云跃'),
            ('罗酱\n个人简历\n工作经历', '罗酱'),
        ]:
            with self.subTest(text=text): self.assertEqual(person_name(text), name)

    def test_no_guesses_from_jobs_file_ids_referees_or_ambiguous_names(self):
        for text in ['', '总经理秘书.pdf', '简历-a23b987c.pdf', '姓名：保密',
                     '工作经历\n联系人 王同学 男 | 35岁', '姓名：张晓林\n姓名：陈佳宁']:
            with self.subTest(text=text): self.assertEqual(person_name(text), '')

    def test_original_filename_has_priority_over_body(self):
        for filename, name in [('张晓林.pdf', '张晓林'), ('李云跃_个人简历_2026.pdf', '李云跃'),
                               ('苏琪-总经理秘书-4年.docx', '苏琪'), ('罗酱的简历.pdf', '罗酱'),
                               ('【行政助理】陈佳宁_本科_2026(1).pdf', '陈佳宁')]:
            with self.subTest(filename=filename): self.assertEqual(filename_name(filename), name)
        self.assertEqual(identity('张晓林-简历.pdf', '姓名：陈佳宁'), ('张晓林', 'resume_filename'))
        self.assertEqual(identity('个人简历.pdf', '姓名：陈佳宁'), ('陈佳宁', 'resume_text'))

    def test_generic_ambiguous_and_random_filenames_never_become_names(self):
        for filename in ['个人简历.pdf', '简历-abcdef12.pdf', '20260930.pdf', '行政管理.pdf',
                         '苏琪_张晓林.pdf', '总经理秘书.docx', 'resume.pdf', '项目经理-最新版.pdf']:
            with self.subTest(filename=filename): self.assertEqual(filename_name(filename), '')
        self.assertEqual(original_filename('../../张晓林.pdf\r\n'), '张晓林.pdf')

    def test_ocr_uncertainty_never_becomes_an_automatic_name(self):
        tsv='level\tpage_num\tblock_num\tpar_num\tline_num\tconf\ttext\n'
        tsv+='5\t1\t1\t1\t1\t96\t张晓林\n5\t1\t2\t1\t1\t96\t个人简历\n'
        self.assertEqual(ocr_identity(tsv),'张晓林')
        self.assertEqual(ocr_identity(tsv.replace('96\t张晓林','40\t张晓林')),'')
