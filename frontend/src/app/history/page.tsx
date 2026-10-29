import { TaskList } from "@/components/task-list";
export default function History() {
  return (
    <>
      <section className="page-heading">
        <p className="eyebrow">YOUR CREATIVE ARCHIVE</p>
        <h1>
          灵感的每一步，<span>都在这里。</span>
        </h1>
        <p className="muted">查看制作进度，重温已经完成的作品。</p>
      </section>
      <TaskList />
    </>
  );
}
